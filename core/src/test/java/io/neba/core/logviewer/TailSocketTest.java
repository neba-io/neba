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
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;


import static java.io.File.createTempFile;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.io.FileUtils.listFiles;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @author Olaf Otto
 */
public class TailSocketTest extends TailTests implements Eventual {
    private Collection<File> availableLogFiles;

    @Mock
    private LogFiles logFiles;

    @Mock
    private Session session;

    @InjectMocks
    private TailSocket testee;

    @Before
    public void prepareRegisteredLogFile() throws Exception {
        this.availableLogFiles = listFiles(getTestLogfileDirectory(), null, true);

        doReturn(availableLogFiles)
                .when(this.logFiles)
                .resolveLogFiles();
    }

    @Before
    public void prepareWebSocketContext() {
        doReturn(getRemote()).when(this.session).getRemote();
        this.testee.onWebSocketConnect(session);
    }

    @After
    public void tearDown() {
        this.testee.onWebSocketClose(-1, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorRequiresNonNullLogFiles() {
        new TailSocket(null);
    }

    @Test
    public void testFollowLogfile() throws Exception {
        File emptyLog = createTempFile("tailsocket-test-", ".log", getTestLogfileDirectory().getParentFile());
        this.availableLogFiles.add(emptyLog);

        testee.onWebSocketText("follow:0.1mb:" + emptyLog.getAbsolutePath());

        sleepUpTo(1, SECONDS);

        verifyNoTextWasSent();

        write(emptyLog, "test line");

        eventually(() -> assertSendTextContains("test line"));
    }

    @Test
    public void testStopFollowLogfile() throws Exception {
        File emptyLog = createTempFile("tailsocket-test-", ".log", getTestLogfileDirectory().getParentFile());
        this.availableLogFiles.add(emptyLog);

        testee.onWebSocketText("follow:0.1mb:" + emptyLog.getAbsolutePath());

        sleepUpTo(1, SECONDS);

        verifyNoTextWasSent();

        testee.onWebSocketText("stop");

        write(emptyLog, "test line");

        sleepUpTo(1, SECONDS);

        verifyNoTextWasSent();
    }

    @Test
    public void testHandlingOfInvalidWebSocketCommand() throws Exception {
        this.testee.onWebSocketText("not:valid");
        verifyCommandIsIgnored();
    }

    @Test
    public void testHandlingOfUnregisteredLogFile() throws Exception {
        tail("/does/not/exist");

        sleepUpTo(1, SECONDS);

        verifyNoTextWasSent();
    }

    @Test
    public void testReplyToPingRequestFromClient() {
        sendPingFromClient();
        verifySocketRepliesAsynchronously("pong");
    }

    @Test(expected = RuntimeException.class)
    public void testHandlingOfIoException() throws Exception {
        withIoExceptionDuringLogfileResolution();
        tail("/does/not/exist");
    }

    private void withIoExceptionDuringLogfileResolution() throws IOException {
        doThrow(new IOException("THIS IS AN EXPECTED TEST EXCEPTION")).when(this.logFiles).resolveLogFiles();
    }

    private void verifySocketRepliesAsynchronously(String s) {
        verify(getRemote()).sendStringByFuture(s);
    }

    private void sendPingFromClient() {
        this.testee.onWebSocketText("ping");
    }

    private void verifyCommandIsIgnored() throws IOException {
        verify(this.logFiles, never()).resolveLogFiles();
    }

    private void tail(String fileName) {
        testee.onWebSocketText("tail:0.1mb:" + pathOf(fileName));
    }
}
