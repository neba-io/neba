/*
  Copyright 2013 the original author or authors.
  <p>
  Licensed under the Apache License, Version 2.0 the "License";
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  <p>
  http://www.apache.org/licenses/LICENSE-2.0
  <p>
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package io.neba.core.logviewer;

import io.neba.core.Eventual;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

import static java.io.File.createTempFile;
import static java.nio.file.Files.move;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @author Olaf Otto
 */
@RunWith(MockitoJUnitRunner.class)
public class TailTest extends TailTests implements Eventual {
    private final ExecutorService executorService = newSingleThreadExecutor();

    private Tail testee;

    @After
    public void tearDown() {
        if (this.testee != null) {
            this.testee.stop();
        }
        this.executorService.shutdownNow();
    }

    @Test
    public void testHandlingOfFileNotFoundExceptionDuringLogFileRotation() throws Exception {
        File logFile = createTempFile("tailsocket-test-", ".log", this.getTestLogfileDirectory().getParentFile());

        followAsynchronously(logFile);

        // Synchronously rotate the file when the first line is received
        uponWriteToRemoteDo(() -> rotate(logFile));

        write(logFile, "first line");

        eventually(() -> assertSendTextContains("first line"));

        // Wait before re-creating the rotated log file to make sure the file is temporarily not found by Tail
        sleepUpTo(100, MILLISECONDS);

        // At this point, the first line was read and the logfile was synchronously rotated immediately thereafter.
        // Tail should have tolerated that the logfile was temporarily gone, and should now notice that the
        // file was rotated as a new blank file is in the original file's place.
        createFile(logFile.getAbsolutePath());

        eventually(() -> assertErrorMessageIsSent("file rotated"));
    }

    @Test
    public void testHandlingOfFileRotation() throws Exception {
        File logFile = createTempFile("tailsocket-test-", ".log", this.getTestLogfileDirectory().getParentFile());

        followAsynchronously(logFile);

        // When the first line is read, synchronously rotate and re-create the log file.
        uponWriteToRemoteDo(() -> {
            rotate(logFile);
            createFile(logFile.getAbsolutePath());
            return null;
        });

        write(logFile, "first line");

        eventually(() -> assertSendTextContains("first line"));
        eventually(() -> assertErrorMessageIsSent("file rotated"));
    }

    @Test
    public void testHandlingOfRemovedLogFile() throws Exception {
        File logFile = createTempFile("tailsocket-test-", ".log", this.getTestLogfileDirectory().getParentFile());
        followAsynchronously(logFile);

        // Synchronously rotate the logfile when the first line is read. Do not re-created it.
        uponWriteToRemoteDo(() -> rotate(logFile));

        write(logFile, "first line");

        eventually(() -> assertSendTextContains("first line"));
        eventually(() -> assertErrorMessageIsSent("file not found"));
    }

    @Test
    public void testHandlingOfIoExceptionWhenSendingLineToClient() throws Exception {
        doThrow(new IOException("THIS IS AN EXPECTED TEST EXCEPTION"))
                .when(this.getRemote())
                .sendBytes(any());

        tailSynchronously("logs/error.log");
    }

    @Test
    public void testPreservationOfWhiteSpaces() throws Exception {
        tailAsynchronously("logs/error-withwhitespaces.log");

        eventually(() ->
                assertSendTextContains(
                        "06.09.2013 15:03:50.719 *ERROR* error message with stacktrace\r\n" +
                                "  at org.apache.sling.jcr.resource.internal.JcrResourceResolverFactoryImpl.getDefaultWorkspaceName(JcrResourceResolverFactoryImpl.java:398)\r\n" +
                                "        at org.apache.sling.jcr.resource.internal.JcrResourceResolver.getResource(JcrResourceResolver.java:817)"));
    }

    @Test
    public void testTailErrorLogIsFullyRead() throws Exception {
        tailAsynchronously("logs/error.log");

        eventually(() -> {
            assertSendTextStartsWith("-- test logs/error.log first line --");
            assertSendTextEndsWith("-- test logs/error.log last line --");
        });
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHandlingOfNullFileArgument() {
        new Tail(mock(RemoteEndpoint.class), null, 1000, Tail.Mode.TAIL);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHandlingOfNullRemoteArgument() {
        new Tail(null, mock(File.class), 1000, Tail.Mode.TAIL);
    }

    private void assertSendTextEndsWith(String s) {
        assertThat(this.getReceivedText().toString()).endsWith(s);
    }

    private void assertSendTextStartsWith(String s) {
        assertThat(this.getReceivedText().toString()).startsWith(s);
    }

    private void assertErrorMessageIsSent(String message) {
        try {
            verify(this.getRemote()).sendString(message);
        } catch (IOException e) {
            // Cannot happen, the remote is a mock.
        }
    }

    private void tailSynchronously(String fileName) {
        this.testee = new Tail(getRemote(), new File(getTestLogfileDirectory(), fileName), 1024L * 1024L, Tail.Mode.TAIL);
        this.testee.run();
    }

    private void tailAsynchronously(String fileName) {
        this.testee = new Tail(getRemote(), new File(getTestLogfileDirectory(), fileName), 1024L * 1024L, Tail.Mode.TAIL);
        this.executorService.execute(this.testee);
    }

    private void followAsynchronously(File logFile) {
        this.testee = new Tail(getRemote(), logFile, 1024, Tail.Mode.FOLLOW);
        this.executorService.execute(this.testee);
    }

    private Path rotate(File file) throws IOException {
        return move(file.toPath(), new File(file.getAbsolutePath() + ".rotated").toPath());
    }

    private void createFile(String logFilePath) throws IOException {
        assertThat(new File(logFilePath).createNewFile())
                .describedAs("Re-creating the logfile was successful")
                .isTrue();
    }
}
