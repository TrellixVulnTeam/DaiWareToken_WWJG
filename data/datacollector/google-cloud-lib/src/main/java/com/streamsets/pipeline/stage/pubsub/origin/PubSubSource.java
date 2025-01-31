/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streamsets.pipeline.stage.pubsub.origin;

import com.google.api.gax.batching.FlowControlSettings;
import com.google.api.gax.batching.FlowController;
import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.grpc.ChannelProvider;
import com.google.api.gax.grpc.ExecutorProvider;
import com.google.api.gax.grpc.InstantiatingExecutorProvider;
import com.google.auth.Credentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.pubsub.v1.PagedResponseWrappers;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.pubsub.v1.ProjectName;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.Topic;
import com.google.pubsub.v1.TopicName;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BasePushSource;
import com.streamsets.pipeline.lib.parser.DataParserFactory;
import com.streamsets.pipeline.lib.util.ThreadUtil;
import com.streamsets.pipeline.stage.lib.CredentialsProviderType;
import com.streamsets.pipeline.stage.pubsub.lib.Errors;
import com.streamsets.pipeline.stage.pubsub.lib.Groups;
import com.streamsets.pipeline.stage.pubsub.lib.MessageProcessor;
import com.streamsets.pipeline.stage.pubsub.lib.MessageReceiverImpl;
import com.streamsets.pipeline.stage.pubsub.lib.MessageReplyConsumerBundle;
import com.streamsets.pipeline.stage.pubsub.lib.MessageProcessorImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import static com.streamsets.pipeline.stage.pubsub.lib.Errors.PUBSUB_01;
import static com.streamsets.pipeline.stage.pubsub.lib.Errors.PUBSUB_02;

public class PubSubSource extends BasePushSource {
  private static final Logger LOG = LoggerFactory.getLogger(PubSubSource.class);
  private static final String CONF_CREDENTIALS_CREDENTIALS_PROVIDER = "conf.credentials.credentialsProvider";
  private static final String PUBSUB_SUBSCRIPTIONS_GET_PERMISSION = "pubsub.subscriptions.get";

  private static final int MAX_INBOUND_MESSAGE_SIZE = 20 * 1024 * 1024; // 20MB API maximum message size.

  private final PubSubSourceConfig conf;

  private CredentialsProvider credentialsProvider;
  private List<Subscriber> subscribers = new ArrayList<>();
  private List<MessageProcessor> messageProcessors = new ArrayList<>();
  private DataParserFactory parserFactory;
  private ExecutorService executor = null;

  PubSubSource(PubSubSourceConfig conf) {
    this.conf = conf;
  }

  @Override
  public void destroy() {
    try {
      LOG.debug("Stopping subscribers");
      subscribers.forEach(Subscriber::stopAsync);
      subscribers.forEach(Subscriber::awaitTerminated);

      LOG.debug("Stopping message processors");
      messageProcessors.forEach(MessageProcessor::stop);
    } finally {
      LOG.info("Stopped {} processing threads", conf.maxThreads);
      subscribers.clear();
      messageProcessors.clear();
    }

    if (executor == null) {
      return;
    }

    LOG.debug("Shutting down executor service");
    executor.shutdown();
    try {
      executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      LOG.warn("Orderly shutdown interrupted.");
    } finally {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  protected List<ConfigIssue> init() {
    List<ConfigIssue> issues = super.init();

    conf.dataFormatConfig.stringBuilderPoolSize = getNumberOfThreads();
    if (conf.dataFormatConfig.init(getContext(), conf.dataFormat, Groups.PUBSUB.name(), "conf.dataFormat.", issues)) {
      parserFactory = conf.dataFormatConfig.getParserFactory();
    }

    issues.addAll(createCredentialsProvider());
    if (issues.isEmpty()) {
      issues.addAll(testPermissions(conf));
    }

    return issues;
  }

  private List<ConfigIssue> createCredentialsProvider() {
    List<ConfigIssue> issues = new ArrayList<>();

    if (conf.credentials.credentialsProvider.equals(CredentialsProviderType.DEFAULT_PROVIDER)) {
      credentialsProvider = SubscriptionAdminSettings.defaultCredentialsProviderBuilder().build();
    } else if (conf.credentials.credentialsProvider.equals(CredentialsProviderType.JSON_PROVIDER)) {
      Credentials credentials = getCredentials(issues);
      credentialsProvider = new FixedCredentialsProvider() {
        @Nullable
        @Override
        public Credentials getCredentials() {
          return credentials;
        }
      };
    }

    return issues;
  }

  private List<ConfigIssue> testPermissions(PubSubSourceConfig conf) {
    List<ConfigIssue> issues = new ArrayList<>();

    TopicAdminSettings settings;
    try {
      settings = TopicAdminSettings.defaultBuilder().setCredentialsProvider(credentialsProvider).build();
    } catch (IOException e) {
      LOG.error(Errors.PUBSUB_04.getMessage(), e.toString(), e);
      issues.add(getContext().createConfigIssue(Groups.CREDENTIALS.name(),
          CONF_CREDENTIALS_CREDENTIALS_PROVIDER, Errors.PUBSUB_04, e.toString()));
      return issues;
    }

    try (TopicAdminClient topicAdminClient = TopicAdminClient.create(settings)) {
      PagedResponseWrappers.ListTopicsPagedResponse listTopicsResponse = topicAdminClient.listTopics(ProjectName
          .newBuilder()
          .setProject(conf.credentials.projectId)
          .build());

      for (Topic topic : listTopicsResponse.iterateAll()) {
        PagedResponseWrappers.ListTopicSubscriptionsPagedResponse listSubscriptionsResponse = topicAdminClient
            .listTopicSubscriptions(
            TopicName.create(conf.credentials.projectId, topic.getNameAsTopicName().getTopic()));
        for (String s : listSubscriptionsResponse.iterateAll()) {
          LOG.info("Subscription '{}' exists for topic '{}'", s, topic.getName());
        }
      }

      List<String> permissions = new LinkedList<>();
      permissions.add(PUBSUB_SUBSCRIPTIONS_GET_PERMISSION);
      SubscriptionName subscriptionName = SubscriptionName.create(conf.credentials.projectId, conf.subscriptionId);
      TestIamPermissionsResponse testedPermissions =
          topicAdminClient.testIamPermissions(subscriptionName.toString(), permissions);
      if (testedPermissions.getPermissionsCount() != 1) {
        issues.add(getContext().createConfigIssue(Groups.CREDENTIALS.name(),
            CONF_CREDENTIALS_CREDENTIALS_PROVIDER, Errors.PUBSUB_03));
      }
    } catch (Exception e) {
      LOG.error(Errors.PUBSUB_04.getMessage(), e.toString(), e);
      issues.add(getContext().createConfigIssue(
          Groups.CREDENTIALS.name(), CONF_CREDENTIALS_CREDENTIALS_PROVIDER,
          Errors.PUBSUB_04,
          e.toString()
      ));
    }
    return issues;
  }

  /**
   * Reads a JSON credentials file for a service account from and returns any errors.
   *
   * @param issues list to append any discovered issues.
   * @return a generic credentials object
   */
  private Credentials getCredentials(List<ConfigIssue> issues) {
    Credentials credentials = null;

    File credentialsFile;
    if (Paths.get(conf.credentials.path).isAbsolute()) {
      credentialsFile = new File(conf.credentials.path);
    } else {
      credentialsFile = new File(getContext().getResourcesDirectory(), conf.credentials.path);
    }

    if (!credentialsFile.exists() || !credentialsFile.isFile()) {
      LOG.error(PUBSUB_01.getMessage(), credentialsFile.getPath());
      issues.add(getContext().createConfigIssue(
          Groups.CREDENTIALS.name(), CONF_CREDENTIALS_CREDENTIALS_PROVIDER,
          PUBSUB_01,
          credentialsFile.getPath()
      ));
      return null;
    }

    try (InputStream in = new FileInputStream(credentialsFile)) {
      credentials = ServiceAccountCredentials.fromStream(in);
    } catch (IOException | IllegalArgumentException e) {
      LOG.error(PUBSUB_02.getMessage(), e);
      issues.add(getContext().createConfigIssue(
          Groups.CREDENTIALS.name(), CONF_CREDENTIALS_CREDENTIALS_PROVIDER,
          PUBSUB_02
      ));
    }

    return credentials;
  }

  @Override
  public int getNumberOfThreads() {
    return conf.maxThreads;
  }

  @Override
  public void produce(Map<String, String> lastOffsets, int maxBatchSize) throws StageException {
    SynchronousQueue<MessageReplyConsumerBundle> workQueue = new SynchronousQueue<>();

    SubscriptionName subscriptionName = SubscriptionName.create(conf.credentials.projectId, conf.subscriptionId);

    executor = Executors.newFixedThreadPool(getNumberOfThreads());

    for (int i = 0; i < conf.maxThreads; i++) {
      MessageProcessor messageProcessor = new MessageProcessorImpl(
          getContext(),
          Math.min(maxBatchSize, conf.basic.maxBatchSize),
          conf.basic.maxWaitTime,
          parserFactory,
          workQueue
      );
      executor.submit(messageProcessor);
      messageProcessors.add(messageProcessor);
    }

    ExecutorProvider executorProvider = InstantiatingExecutorProvider.newBuilder()
        .setExecutorThreadCount(conf.advanced.numThreadsPerSubscriber)
        .build();

    ChannelProvider channelProvider = getChannelProvider();
    FlowControlSettings flowControlSettings = getFlowControlSettings();

    for (int i = 0; i < conf.advanced.numSubscribers; i++) {
      Subscriber s = Subscriber.defaultBuilder(subscriptionName, new MessageReceiverImpl(workQueue))
          .setCredentialsProvider(credentialsProvider)
          .setExecutorProvider(executorProvider)
          .setChannelProvider(channelProvider)
          .setFlowControlSettings(flowControlSettings)
          .build();
      s.addListener(new Subscriber.Listener() {
        @Override
        public void failed(Subscriber.State from, Throwable failure) {
          LOG.error("Exception thrown in Subscriber: {}", failure.toString(), failure);
          LOG.error("Subscriber state: {}", from.toString());
          Throwables.propagate(failure);
        }
      }, MoreExecutors.directExecutor());
      subscribers.add(s);
    }

    try {
      subscribers.forEach(Subscriber::startAsync);
    } finally {
      LOG.info("Started {} subscribers.", conf.maxThreads);
    }

    while (!getContext().isStopped()) {
      ThreadUtil.sleep(1000);
    }
  }

  /**
   * Returns a flow control setting such that a subscriber will block if it has buffered more messages than can
   * be processed in a single batch times the number of record processors. Since the flow control settings are
   * per subscriber, we should divide by the number of subscribers to avoid buffering too much data in each subscriber.
   *
   * @return settings based on the stage configuration.
   */
  private FlowControlSettings getFlowControlSettings() {
    return FlowControlSettings.newBuilder()
        .setLimitExceededBehavior(FlowController.LimitExceededBehavior.Block)
        .setMaxOutstandingElementCount((long) conf.basic.maxBatchSize * conf.maxThreads / conf.advanced.numSubscribers)
        .build();
  }

  /**
   * Creates a channel provider shared by each subscriber. It is basically the default ChannelProvider with the
   * exception that it can be configured with a custom endpoint, for example when running against the PubSub Emulator.
   *
   * @return channel provider based on the stage configuration.
   */
  private ChannelProvider getChannelProvider() {
    return SubscriptionAdminSettings.defaultChannelProviderBuilder()
        .setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE)
        .setEndpoint(Strings.isNullOrEmpty(conf.advanced.customEndpoint) ? SubscriptionAdminSettings
            .getDefaultEndpoint() : conf.advanced.customEndpoint)
        .build();
  }
}
