// Copyright (C) 2018 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.ericsson.gerrit.plugins.highavailability.forwarder;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ericsson.gerrit.plugins.highavailability.Configuration;
import com.ericsson.gerrit.plugins.highavailability.forwarder.ForwardedIndexingHandler.Operation;
import com.ericsson.gerrit.plugins.highavailability.index.ChangeChecker;
import com.ericsson.gerrit.plugins.highavailability.index.ChangeCheckerImpl;
import com.google.gerrit.entities.Change;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.gerrit.server.util.time.TimeUtil;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.class)
public class ForwardedIndexChangeHandlerTest {

  private static final int TEST_CHANGE_NUMBER = 123;
  private static String TEST_PROJECT = "test/project";
  private static String TEST_CHANGE_ID = TEST_PROJECT + "~" + TEST_CHANGE_NUMBER;
  private static final boolean CHANGE_EXISTS = true;
  private static final boolean CHANGE_DOES_NOT_EXIST = false;
  private static final boolean CHANGE_UP_TO_DATE = true;
  private static final boolean CHANGE_OUTDATED = false;

  @Rule public ExpectedException exception = ExpectedException.none();
  @Mock private ChangeIndexer indexerMock;
  @Mock private ChangeNotes changeNotes;
  @Mock private Configuration configMock;
  @Mock private Configuration.Index indexMock;
  @Mock private ScheduledExecutorService indexExecutorMock;
  @Mock private OneOffRequestContext ctxMock;
  @Mock private ChangeCheckerImpl.Factory changeCheckerFactoryMock;
  @Mock private ChangeChecker changeCheckerAbsentMock;
  @Mock private ChangeChecker changeCheckerPresentMock;
  private ForwardedIndexChangeHandler handler;
  private Change.Id id;

  @Before
  public void setUp() throws Exception {
    id = Change.id(TEST_CHANGE_NUMBER);
    Change change = new Change(null, id, null, null, TimeUtil.nowTs());
    when(changeNotes.getChange()).thenReturn(change);
    when(configMock.index()).thenReturn(indexMock);
    when(indexMock.numStripedLocks()).thenReturn(10);
    when(changeCheckerFactoryMock.create(any())).thenReturn(changeCheckerAbsentMock);
    handler =
        new ForwardedIndexChangeHandler(
            indexerMock, configMock, indexExecutorMock, ctxMock, changeCheckerFactoryMock);
  }

  @Test
  public void changeIsIndexedWhenUpToDate() throws Exception {
    setupChangeAccessRelatedMocks(CHANGE_EXISTS, CHANGE_UP_TO_DATE);
    handler.index(TEST_CHANGE_ID, Operation.INDEX, Optional.empty());
    verify(indexerMock, times(1)).index(any(Change.class));
  }

  @Test
  public void changeIsDeletedFromIndex() throws Exception {
    handler.index(TEST_CHANGE_ID, Operation.DELETE, Optional.empty());
    verify(indexerMock, times(1)).delete(id);
  }

  @Test
  public void shouldSetAndUnsetForwardedContext() throws Exception {
    setupChangeAccessRelatedMocks(CHANGE_EXISTS, CHANGE_UP_TO_DATE);
    // this doAnswer is to allow to assert that context is set to forwarded
    // while cache eviction is called.
    doAnswer(
            (Answer<Void>)
                invocation -> {
                  assertThat(Context.isForwardedEvent()).isTrue();
                  return null;
                })
        .when(indexerMock)
        .index(any(Change.class));

    assertThat(Context.isForwardedEvent()).isFalse();
    handler.index(TEST_CHANGE_ID, Operation.INDEX, Optional.empty());
    assertThat(Context.isForwardedEvent()).isFalse();

    verify(indexerMock, times(1)).index(any(Change.class));
  }

  @Test
  public void shouldSetAndUnsetForwardedContextEvenIfExceptionIsThrown() throws Exception {
    setupChangeAccessRelatedMocks(CHANGE_EXISTS, CHANGE_UP_TO_DATE);
    doAnswer(
            (Answer<Void>)
                invocation -> {
                  assertThat(Context.isForwardedEvent()).isTrue();
                  throw new IOException("someMessage");
                })
        .when(indexerMock)
        .index(any(Change.class));

    assertThat(Context.isForwardedEvent()).isFalse();
    IOException thrown =
        assertThrows(
            IOException.class,
            () -> handler.index(TEST_CHANGE_ID, Operation.INDEX, Optional.empty()));
    assertThat(thrown).hasMessageThat().isEqualTo("someMessage");
    assertThat(Context.isForwardedEvent()).isFalse();

    verify(indexerMock, times(1)).index(any(Change.class));
  }

  private void setupChangeAccessRelatedMocks(boolean changeExists, boolean changeIsUpToDate)
      throws IOException {
    if (changeExists) {
      when(changeCheckerFactoryMock.create(TEST_CHANGE_ID)).thenReturn(changeCheckerPresentMock);
      when(changeCheckerPresentMock.getChangeNotes()).thenReturn(Optional.of(changeNotes));
    }

    when(changeCheckerPresentMock.isChangeUpToDate(any())).thenReturn(changeIsUpToDate);
  }
}
