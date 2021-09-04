/* Copyright 2019 Blockchain Technology Partners
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
     http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
------------------------------------------------------------------------------*/
package com.blockchaintp.sawtooth.timekeeper;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.blockchaintp.keymanager.InMemoryKeyManager;
import com.blockchaintp.keymanager.KeyManager;
import com.blockchaintp.sawtooth.messaging.ZmqStream;
import com.blockchaintp.sawtooth.timekeeper.processor.TimeKeeperTransactionHandler;
import com.blockchaintp.utils.LogUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sawtooth.sdk.messaging.Stream;
import sawtooth.sdk.processor.TransactionHandler;
import sawtooth.sdk.processor.TransactionProcessor;

/**
 * A basic Main class for TimeKeeperTransactionProcessor.
 */
public final class TimeKeeperTransactionProcessorMain {

  private static final int DEFAULT_TK_UPDATE_SECONDS = 20;
  private static final String DEFAULT_CONNECT_STRING = "tcp://localhost:4004";

  private static final Logger LOGGER = LoggerFactory.getLogger(TimeKeeperTransactionProcessorMain.class);

  private static final String OPT_CONNECT = "C";
  private static final String OPT_PERIOD = "p";
  private static final String OPT_SUBMITTER = "s";
  private static final String OPT_TP = "t";
  private static final String OPT_BOTH = "b";
  private static final String OPT_VERBOSE = "v";

  int vCount = 0;
  int updatePeriod = DEFAULT_TK_UPDATE_SECONDS;
  String connectStr = DEFAULT_CONNECT_STRING;
  boolean startTp = true;
  boolean startSubmitter = true;

  /**
   * A basic main method for this transaction processor.
   * @param args at this time only one argument the address of the validator
   *             component endpoint, e.g. tcp://localhost:4004
   */
  public static void main(final String[] args) {
    final TimeKeeperTransactionProcessorMain main = new TimeKeeperTransactionProcessorMain();
    try {
      main.parseArgs(args);
    } catch (InvalidCommandException e) {
      System.err.println(e.getMessage()); //NOSONAR
      System.exit(-1);
    }

  }

  public void start() {
    LogUtils.setRootLogLevel(vCount);

    ScheduledExecutorService clockExecutor = Executors.newSingleThreadScheduledExecutor();

    Stream stream = new ZmqStream(connectStr); //NOSONAR

    if (startSubmitter) {
      KeyManager keyManager = InMemoryKeyManager.create();
      clockExecutor.scheduleWithFixedDelay(new TimeKeeperRunnable(keyManager, stream), updatePeriod, updatePeriod,
          TimeUnit.SECONDS);
    }

    if (startTp) {
      TransactionProcessor transactionProcessor = new TransactionProcessor(connectStr);
      TransactionHandler handler = new TimeKeeperTransactionHandler();
      transactionProcessor.addHandler(handler);

      Thread thread = new Thread(transactionProcessor);
      thread.start();
      try {
        thread.join();
        clockExecutor.shutdownNow();
      } catch (InterruptedException exc) {
        LOGGER.warn("TransactionProcessor was interrupted");
        Thread.currentThread().interrupt();
      }
    }
  }

  private TimeKeeperTransactionProcessorMain() {
    // private constructor for utility class
  }

  private Options createOptions() {
    Option connect = Option.builder("C")
                            .argName("endpoint")
                            .longOpt("connect")
                            .hasArg()
                            .required(false)
                            .desc("Give the validator ZMQ endpoint to connect to")
                            .build();

    Option period = Option.builder("p")
                          .argName("period")
                          .longOpt("period")
                          .hasArg()
                          .desc("Ho often to send time updates")
                          .build();

    Option verbose = Option.builder("v")
                          .desc("Verbosity. Repeat for greater detail.")
                          .build();


    Option submitter = Option.builder("s")
                          .longOpt("submitter")
                          .desc("Set to run the submitter")
                          .build();

    Option tp = Option.builder("t")
                      .longOpt("tp")
                      .desc("Set to run the transaction processr")
                      .build();

    Option both = Option.builder("b")
                        .longOpt("both")
                        .desc("Set to run both the tp and the submitter [default]")
                        .build();

    OptionGroup mode = new OptionGroup();
    mode.setRequired(false);
    mode.addOption(submitter);
    mode.addOption(both);
    mode.addOption(tp);

    Options options = new Options();
    options.addOption(connect);
    options.addOption(period);
    options.addOption(verbose);
    options.addOptionGroup(mode);

    return options;
  }

  private void parseArgs(String[] args) throws InvalidCommandException {
    Options options = createOptions();

    CommandLineParser parser = new org.apache.commons.cli.DefaultParser();
    CommandLine cmd = null;
    try {
      cmd = parser.parse(options, args);
    } catch (org.apache.commons.cli.ParseException e) {
      throw new InvalidCommandException(String.format("Invalid command line arguments: %s", e.getMessage()));
    }

    if (cmd.hasOption(OPT_CONNECT)) {
      connectStr = cmd.getOptionValue(OPT_CONNECT);
    }

    if (cmd.hasOption(OPT_PERIOD)) {
      var periodStr = cmd.getOptionValue(OPT_PERIOD);
      try {
        updatePeriod = Integer.parseInt(periodStr);
      } catch (NumberFormatException nfe) {
        throw new InvalidCommandException(String.format("Invalid format specified for period: %s", periodStr));
      }
    }

    if (cmd.hasOption(OPT_VERBOSE)) {
      for (Option o: cmd.getOptions()) {
        if (o.getOpt().equals(OPT_VERBOSE)) {
          vCount++;
        }
      }
    }

    startTp = true;
    startSubmitter = true;

    if (cmd.hasOption(OPT_SUBMITTER)) {
      startSubmitter = true; //NOSONAR
      startTp = false;
    } else if (cmd.hasOption(OPT_TP)) {
      startSubmitter = false;
      startTp = true; //NOSONAR
    } else if (cmd.hasOption(OPT_BOTH)) {
      startSubmitter = true; //NOSONAR
      startTp = true; //NOSONAR
    }

    List<String> remainder = cmd.getArgList();

    if (!remainder.isEmpty()) {
      connectStr = remainder.get(0);
    }
  }
}
