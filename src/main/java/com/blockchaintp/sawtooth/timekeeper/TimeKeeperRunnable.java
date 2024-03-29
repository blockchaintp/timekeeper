/*
 * Copyright © 2023 Paravela Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.blockchaintp.sawtooth.timekeeper;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.blockchaintp.keymanager.KeyManager;
import com.blockchaintp.sawtooth.SawtoothClientUtils;
import com.blockchaintp.sawtooth.timekeeper.exceptions.TimeKeeperException;
import com.blockchaintp.sawtooth.timekeeper.protobuf.TimeKeeperUpdate;
import com.blockchaintp.sawtooth.timekeeper.protobuf.TimeKeeperVersion;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sawtooth.sdk.messaging.Future;
import sawtooth.sdk.messaging.Stream;
import sawtooth.sdk.processor.exceptions.ValidatorConnectionError;
import sawtooth.sdk.protobuf.Batch;
import sawtooth.sdk.protobuf.ClientBatchSubmitRequest;
import sawtooth.sdk.protobuf.ClientBatchSubmitResponse;
import sawtooth.sdk.protobuf.Message;
import sawtooth.sdk.protobuf.Transaction;

/**
 * TimeKeeperRunnable is designed to be run in a fixed schedule thread pool, where it will
 * periodically submit a TimeKeeperUpdate.
 */
public final class TimeKeeperRunnable implements Runnable {

  private static final Logger LOGGER = LoggerFactory.getLogger(TimeKeeperRunnable.class);

  /**
   * The maximum number of rounds that may be skipped.
   */
  private static final int MAX_SKIPS = 32;

  private final KeyManager keyManager;
  private final String recordAddress;

  private final Stream stream;

  private int backoffCounter;
  private int skipCounter;

  /**
   * Main constructor.
   *
   * @param kmgr
   *          A key manager implementation which will provide a keys for the transactions,
   * @param argStream
   *          the stream connecting to the validator.
   */
  public TimeKeeperRunnable(final KeyManager kmgr, final Stream argStream) {
    this.keyManager = kmgr;
    this.stream = argStream;
    this.recordAddress = Namespace.makeAddress(Namespace.getNameSpace(), this.keyManager.getPublicKeyInHex());
  }

  @Override
  public void run() {
    final Clock clock = Clock.systemUTC();
    final Instant instant = clock.instant();
    final Timestamp ts = Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano())
        .build();
    final TimeKeeperUpdate update = TimeKeeperUpdate.newBuilder().setVersion(TimeKeeperVersion.V_2_0).setTimeUpdate(ts)
        .build();

    final List<String> inputAddresses = Arrays.asList(this.recordAddress, Namespace.TIMEKEEPER_GLOBAL_RECORD);
    final List<String> outputAddresses = Arrays.asList(this.recordAddress, Namespace.TIMEKEEPER_GLOBAL_RECORD);
    final Transaction updateTransaction = SawtoothClientUtils.makeSawtoothTransaction(this.keyManager,
        Namespace.TIMEKEEPER_FAMILY_NAME, Namespace.TIMEKEEPER_FAMILY_VERSION_1_0, inputAddresses, outputAddresses,
        Arrays.asList(), update.toByteString());

    final Batch batch = SawtoothClientUtils.makeSawtoothBatch(this.keyManager, Arrays.asList(updateTransaction));

    try {
      LOGGER.debug("Sending a participant time update {} time={}", this.keyManager.getPublicKeyInHex(),
          new Date(Timestamps.toMillis(ts)));
      if (skipCounter < backoffCounter) {
        skipCounter++;
        return;
      }
      skipCounter = 0;
      sendBatch(batch);
      if (backoffCounter > 0) {
        backoffCounter -= 1;
        backoffCounter = Math.max(backoffCounter, 0);
        LOGGER.warn("Successfully updated time marker after backoff, reducing backoff to {} intervals", backoffCounter);
      }
    } catch (TimeKeeperException exc) {
      backoffCounter = Math.max(1, 2 * backoffCounter);
      backoffCounter = Math.min(MAX_SKIPS, backoffCounter);
      LOGGER.warn("Error updating TimeKeeper records, increasing backoff to {} intervals", backoffCounter);
    }
  }

  private void sendBatch(final Batch batch) throws TimeKeeperException {
    final ClientBatchSubmitRequest cbsReq = ClientBatchSubmitRequest.newBuilder().addBatches(batch).build();
    final Future streamToValidator = this.stream.send(Message.MessageType.CLIENT_BATCH_SUBMIT_REQUEST,
        cbsReq.toByteString());
    try {
      final ByteString result = streamToValidator.getResult();
      final ClientBatchSubmitResponse submitResponse = ClientBatchSubmitResponse.parseFrom(result);
      LOGGER.debug("Batch submitted {}", batch.getHeaderSignature());
      if (submitResponse.getStatus() != ClientBatchSubmitResponse.Status.OK) {
        LOGGER.warn("Batch submit response resulted in error: {}", submitResponse.getStatus());
        throw new TimeKeeperException(
            String.format("Batch submit response resulted in error: %s", submitResponse.getStatus()));
      }
    } catch (InterruptedException e) {
      final TimeKeeperException tke = new TimeKeeperException(
          String.format("Sawtooth validator interrupts exception. Details: %s", e.getMessage()));
      tke.initCause(e);
      Thread.currentThread().interrupt();
      throw tke;
    } catch (ValidatorConnectionError e) {
      final TimeKeeperException tke = new TimeKeeperException(
          String.format("Sawtooth validator connection error. Details: %s", e.getMessage()));
      tke.initCause(e);
      throw tke;
    } catch (InvalidProtocolBufferException e) {
      final TimeKeeperException tke = new TimeKeeperException(
          String.format("Invalid protocol buffer exception. Details: %s", e.getMessage()));
      tke.initCause(e);
      throw tke;
    }
  }
}
