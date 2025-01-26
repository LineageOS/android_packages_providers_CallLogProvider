/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calllogbackup;

import static com.android.calllogbackup.CallLogBackupAgent.SELECTION_CALL_DATE_AND_NUMBER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import com.google.common.collect.ImmutableList;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.CallLog;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.calllogbackup.CallLogBackupAgent.Call;
import com.android.calllogbackup.CallLogBackupAgent.CallLogBackupState;
import com.android.calllogbackup.Flags;
import com.android.internal.annotations.VisibleForTesting;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.OngoingStubbing;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Test cases for {@link com.android.providers.contacts.CallLogBackupAgent}
 */
@SmallTest
public class CallLogBackupAgentTest {
    static final String TELEPHONY_COMPONENT
            = "com.android.phone/com.android.services.telephony.TelephonyConnectionService";
    static final String TEST_PHONE_ACCOUNT_HANDLE_SUB_ID = "666";
    static final int TEST_PHONE_ACCOUNT_HANDLE_SUB_ID_INT = 666;
    static final String TEST_PHONE_ACCOUNT_HANDLE_ICC_ID = "891004234814455936F";

    public int backupRestoreLoggerSuccessCount = 0;
    public int backupRestoreLoggerFailCount = 0;

    @Mock DataInput mDataInput;
    @Mock DataOutput mDataOutput;
    @Mock BackupDataOutput mBackupDataOutput;
    @Mock Cursor mCursor;

    private Context mContext;

    private final CallLogBackupAgent.BackupRestoreEventLoggerProxy mBackupRestoreEventLoggerProxy =
            new CallLogBackupAgent.BackupRestoreEventLoggerProxy() {
        @Override
        public void logItemsBackedUp(String dataType, int count) {
            backupRestoreLoggerSuccessCount += count;
        }

        @Override
        public void logItemsBackupFailed(String dataType, int count, String error) {
            backupRestoreLoggerFailCount += count;
        }

        @Override
        public void logItemsRestored(String dataType, int count) {
            backupRestoreLoggerSuccessCount += count;
        }

        @Override
        public void logItemsRestoreFailed(String dataType, int count, String error) {
            backupRestoreLoggerFailCount += count;
        }
    };

    CallLogBackupAgent mCallLogBackupAgent;

    MockitoHelper mMockitoHelper = new MockitoHelper();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();


    @Before
    public void setUp() throws Exception {
        mMockitoHelper.setUp(getClass());

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Since we're testing a system app, AppDataDirGuesser doesn't find our
        // cache dir, so set it explicitly.
        System.setProperty("dexmaker.dexcache", mContext.getCacheDir().toString());

        MockitoAnnotations.initMocks(this);
        mCallLogBackupAgent = new CallLogBackupAgent();
        mCallLogBackupAgent.setBackupRestoreEventLoggerProxy(mBackupRestoreEventLoggerProxy);
    }

    @After
    public void tearDown() throws Exception {
        mMockitoHelper.tearDown();
    }

    @Test
    public void testReadState_NoCall() throws Exception {
        when(mDataInput.readInt()).thenThrow(new EOFException());

        CallLogBackupState state = mCallLogBackupAgent.readState(mDataInput);

        assertEquals(state.version, CallLogBackupAgent.VERSION_NO_PREVIOUS_STATE);
        assertEquals(state.callIds.size(), 0);
    }

    @Test
    public void testReadState_OneCall() throws Exception {
        when(mDataInput.readInt()).thenReturn(
                1 /* version */,
                1 /* size */,
                101 /* call-ID */ );

        CallLogBackupState state = mCallLogBackupAgent.readState(mDataInput);

        assertEquals(1, state.version);
        assertEquals(1, state.callIds.size());
        assertTrue(state.callIds.contains(101));
    }

    /**
     * Verifies that attempting to restore from a version newer than what the backup agent defines
     * will result in no restored rows.
     */
    @Test
    public void testRestoreFromHigherVersion() throws Exception {
        // The backup format is not well structured, and consists of a bunch of persisted bytes, so
        // making the mock data is a bit gross.
        BackupDataInput backupDataInput = Mockito.mock(BackupDataInput.class);
        when(backupDataInput.getKey()).thenReturn("1");
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(new ByteArrayOutputStream());
        dataOutputStream.writeInt(10000); // version
        byte[] data = byteArrayOutputStream.toByteArray();
        when(backupDataInput.getDataSize()).thenReturn(data.length);
        when(backupDataInput.readEntityData(any(), anyInt(), anyInt())).thenAnswer(
                invocation -> {
                    byte[] bytes = invocation.getArgument(0);
                    System.arraycopy(data, 0, bytes, 0, data.length);
                    return null;
                }
        );

        // Well, this is awkward.  BackupDataInput has no way to get the number of data elements
        // it contains.  So we'll mock out "readNextHeader" to emulate there being some non-zero
        // number of items to restore.
        final int[] executionLimit = {1};
        when(backupDataInput.readNextHeader()).thenAnswer(
                invocation -> {
                    executionLimit[0]--;
                    return executionLimit[0] >= 0;
                }
        );

        mCallLogBackupAgent.attach(mContext);
        mCallLogBackupAgent.onRestore(backupDataInput, Integer.MAX_VALUE, null);

        assertEquals(1, backupRestoreLoggerFailCount);
        assertEquals(0, backupRestoreLoggerSuccessCount);
    }

    @Test
    public void testReadState_MultipleCalls() throws Exception {
        when(mDataInput.readInt()).thenReturn(
                1 /* version */,
                2 /* size */,
                101 /* call-ID */,
                102 /* call-ID */);

        CallLogBackupState state = mCallLogBackupAgent.readState(mDataInput);

        assertEquals(1, state.version);
        assertEquals(2, state.callIds.size());
        assertTrue(state.callIds.contains(101));
        assertTrue(state.callIds.contains(102));
    }

    @Test
    public void testWriteState_NoCalls() throws Exception {
        CallLogBackupState state = new CallLogBackupState();
        state.version = CallLogBackupAgent.VERSION;
        state.callIds = new TreeSet<>();

        mCallLogBackupAgent.writeState(mDataOutput, state);

        InOrder inOrder = Mockito.inOrder(mDataOutput);
        inOrder.verify(mDataOutput).writeInt(CallLogBackupAgent.VERSION);
        inOrder.verify(mDataOutput).writeInt(0 /* size */);
    }

    @Test
    public void testWriteState_OneCall() throws Exception {
        CallLogBackupState state = new CallLogBackupState();
        state.version = CallLogBackupAgent.VERSION;
        state.callIds = new TreeSet<>();
        state.callIds.add(101);

        mCallLogBackupAgent.writeState(mDataOutput, state);

        InOrder inOrder = Mockito.inOrder(mDataOutput);
        inOrder.verify(mDataOutput).writeInt(CallLogBackupAgent.VERSION);
        inOrder.verify(mDataOutput).writeInt(1);
        inOrder.verify(mDataOutput).writeInt(101 /* call-ID */);
    }

    @Test
    public void testWriteState_MultipleCalls() throws Exception {
        CallLogBackupState state = new CallLogBackupState();
        state.version = CallLogBackupAgent.VERSION;
        state.callIds = new TreeSet<>();
        state.callIds.add(101);
        state.callIds.add(102);
        state.callIds.add(103);

        mCallLogBackupAgent.writeState(mDataOutput, state);

        InOrder inOrder = Mockito.inOrder(mDataOutput);
        inOrder.verify(mDataOutput).writeInt(CallLogBackupAgent.VERSION);
        inOrder.verify(mDataOutput).writeInt(3 /* size */);
        inOrder.verify(mDataOutput).writeInt(101 /* call-ID */);
        inOrder.verify(mDataOutput).writeInt(102 /* call-ID */);
        inOrder.verify(mDataOutput).writeInt(103 /* call-ID */);
    }

    @Test
    public void testRunBackup_NoCalls() {
        CallLogBackupState state = new CallLogBackupState();
        state.version = CallLogBackupAgent.VERSION;
        state.callIds = new TreeSet<>();
        List<Call> calls = new LinkedList<>();

        mCallLogBackupAgent.runBackup(state, mBackupDataOutput, calls);

        // Ensure the {@link BackupRestoreEventLogger} is not notified as no calls were backed up:
        assertEquals(backupRestoreLoggerSuccessCount, 0);
        assertEquals(backupRestoreLoggerFailCount, 0);

        Mockito.verifyNoMoreInteractions(mBackupDataOutput);
    }

    @Test
    public void testRunBackup_OneNewCall_ErrorAddingCall() throws Exception {
        CallLogBackupState state = new CallLogBackupState();
        state.version = CallLogBackupAgent.VERSION;
        state.callIds = new TreeSet<>();
        List<Call> calls = new LinkedList<>();
        calls.add(makeCall(101, 0L, 0L, "555-5555"));

        // Throw an exception when the call is added to the backup:
        when(mBackupDataOutput.writeEntityData(any(byte[].class), anyInt()))
                .thenThrow(IOException.class);
        mCallLogBackupAgent.runBackup(state, mBackupDataOutput, calls);

        // Ensure the {@link BackupRestoreEventLogger} is informed of the failed backed up call:
        assertEquals(backupRestoreLoggerSuccessCount, 0);
        assertEquals(backupRestoreLoggerFailCount, 1);
    }

    @Test
    public void testRunBackup_OneNewCall_NullBackupDataOutput() {
        CallLogBackupState state = new CallLogBackupState();
        state.version = CallLogBackupAgent.VERSION;
        state.callIds = new TreeSet<>();
        List<Call> calls = new LinkedList<>();
        calls.add(makeCall(101, 0L, 0L, "555-5555"));

        // Invoke runBackup() with a null value for BackupDataOutput causing an exception:
        mCallLogBackupAgent.runBackup(state, null, calls);

        // Ensure the {@link BackupRestoreEventLogger} is informed of the failed backed up call:
        assertEquals(backupRestoreLoggerSuccessCount, 0);
        assertEquals(backupRestoreLoggerFailCount, 1);
    }

    @Test
    public void testRunBackup_OneNewCall() throws Exception {
        CallLogBackupState state = new CallLogBackupState();
        state.version = CallLogBackupAgent.VERSION;
        state.callIds = new TreeSet<>();
        List<Call> calls = new LinkedList<>();
        calls.add(makeCall(101, 0L, 0L, "555-5555"));
        mCallLogBackupAgent.runBackup(state, mBackupDataOutput, calls);

        // Ensure the {@link BackupRestoreEventLogger} is informed of the backed up call:
        assertEquals(backupRestoreLoggerSuccessCount, 1);
        assertEquals(backupRestoreLoggerFailCount, 0);

        verify(mBackupDataOutput).writeEntityHeader(eq("101"), anyInt());
        verify(mBackupDataOutput).writeEntityData(any(byte[].class), anyInt());
    }

    /*
        Test PhoneAccountHandle Migration process during back up
     */
    @Test
    public void testReadCallFromCursorForPhoneAccountMigrationBackup() {
        Map<Integer, String> subscriptionInfoMap = new HashMap<>();
        subscriptionInfoMap.put(TEST_PHONE_ACCOUNT_HANDLE_SUB_ID_INT,
                TEST_PHONE_ACCOUNT_HANDLE_ICC_ID);
        mCallLogBackupAgent.mSubscriptionInfoMap = subscriptionInfoMap;

        // Mock telephony component name and expect the Sub ID is converted to Icc ID
        // and the pending status is 1 when backup
        mockCursor(mCursor, true);
        Call call = mCallLogBackupAgent.readCallFromCursor(mCursor);
        assertEquals(TEST_PHONE_ACCOUNT_HANDLE_ICC_ID, call.accountId);
        assertEquals(1, call.isPhoneAccountMigrationPending);

        // Mock non-telephony component name and expect the Sub ID not converted to Icc ID
        // and pending status is 0 when backup.
        mockCursor(mCursor, false);
        call = mCallLogBackupAgent.readCallFromCursor(mCursor);
        assertEquals(TEST_PHONE_ACCOUNT_HANDLE_SUB_ID, call.accountId);
        assertEquals(0, call.isPhoneAccountMigrationPending);
    }

    @Test
    public void testReadCallFromCursor_WithNullAccountComponentName() {
        testReadCallFromCursor_WithNullField(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME);
    }

    @Test
    public void testReadCallFromCursor_WithNullNumber() {
        testReadCallFromCursor_WithNullField(CallLog.Calls.NUMBER);
    }

    @Test
    public void testReadCallFromCursor_WithNullPostDialDigits() {
        testReadCallFromCursor_WithNullField(CallLog.Calls.POST_DIAL_DIGITS);
    }

    @Test
    public void testReadCallFromCursor_WithNullViaNumber() {
        testReadCallFromCursor_WithNullField(CallLog.Calls.VIA_NUMBER);
    }

    @Test
    public void testReadCallFromCursor_WithNullPhoneAccountId() {
        testReadCallFromCursor_WithNullField(CallLog.Calls.PHONE_ACCOUNT_ID);
    }

    @Test
    public void testReadCallFromCursor_WithNullCallAccountAddress() {
        testReadCallFromCursor_WithNullField(CallLog.Calls.PHONE_ACCOUNT_ADDRESS);
    }

    @Test
    public void testReadCallFromCursor_WithNullCallScreeningAppName() {
        testReadCallFromCursor_WithNullField(CallLog.Calls.CALL_SCREENING_APP_NAME);
    }

    @Test
    public void testReadCallFromCursor_WithNullCallScreeningComponentName() {
        testReadCallFromCursor_WithNullField(CallLog.Calls.CALL_SCREENING_COMPONENT_NAME);
    }

    @Test
    public void testReadCallFromCursor_WithNullMissedReason() {
        testReadCallFromCursor_WithNullField(CallLog.Calls.MISSED_REASON);
    }

    private void testReadCallFromCursor_WithNullField(String field) {
        Map<Integer, String> subscriptionInfoMap = new HashMap<>();
        subscriptionInfoMap.put(TEST_PHONE_ACCOUNT_HANDLE_SUB_ID_INT,
            TEST_PHONE_ACCOUNT_HANDLE_ICC_ID);
        mCallLogBackupAgent.mSubscriptionInfoMap = subscriptionInfoMap;

        //read from cursor and not throw exception
        mockCursorWithNullFields(mCursor, field);
        Call call = mCallLogBackupAgent.readCallFromCursor(mCursor);
    }

    @Test
    public void testRunBackup_MultipleCall() throws Exception {
        CallLogBackupState state = new CallLogBackupState();
        state.version = CallLogBackupAgent.VERSION;
        state.callIds = new TreeSet<>();
        List<Call> calls = new LinkedList<>();
        calls.add(makeCall(101, 0L, 0L, "555-1234"));
        calls.add(makeCall(102, 0L, 0L, "555-5555"));

        mCallLogBackupAgent.runBackup(state, mBackupDataOutput, calls);

        // Ensure the {@link BackupRestoreEventLogger} is informed of the 2 backed up calls:
        assertEquals(backupRestoreLoggerSuccessCount, 2);
        assertEquals(backupRestoreLoggerFailCount, 0);

        InOrder inOrder = Mockito.inOrder(mBackupDataOutput);
        inOrder.verify(mBackupDataOutput).writeEntityHeader(eq("101"), anyInt());
        inOrder.verify(mBackupDataOutput).
                writeEntityData(any(byte[].class), anyInt());
        inOrder.verify(mBackupDataOutput).writeEntityHeader(eq("102"), anyInt());
        inOrder.verify(mBackupDataOutput).
                writeEntityData(any(byte[].class), anyInt());
    }

    @Test
    public void testRunBackup_PartialMultipleCall() throws Exception {
        CallLogBackupState state = new CallLogBackupState();

        state.version = CallLogBackupAgent.VERSION;
        state.callIds = new TreeSet<>();
        state.callIds.add(101);

        List<Call> calls = new LinkedList<>();
        calls.add(makeCall(101, 0L, 0L, "555-1234"));
        calls.add(makeCall(102, 0L, 0L, "555-5555"));

        mCallLogBackupAgent.runBackup(state, mBackupDataOutput, calls);

        // Ensure the {@link BackupRestoreEventLogger} is informed of the 2 backed up calls:
        assertEquals(backupRestoreLoggerSuccessCount, 2);
        assertEquals(backupRestoreLoggerFailCount, 0);

        InOrder inOrder = Mockito.inOrder(mBackupDataOutput);
        inOrder.verify(mBackupDataOutput).writeEntityHeader(eq("102"), anyInt());
        inOrder.verify(mBackupDataOutput).
                writeEntityData(any(byte[].class), anyInt());
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_CALL_LOG_RESTORE_DEDUPLICATION_ENABLED})
    @RequiresFlagsDisabled({Flags.FLAG_BATCH_DEDUPLICATION_ENABLED})
    public void testRestore_DeduplicationEnabled_BatchDisabled_DuplicateEntry_Deduplicates()
            throws Exception {
        FakeCallLogBackupAgent backupAgent = new FakeCallLogBackupAgent();
        backupAgent.setBackupRestoreEventLoggerProxy(mBackupRestoreEventLoggerProxy);
        backupAgent.attach(mContext);

        // Get the initial count of call log entries
        ContentResolver contentResolver = backupAgent.getContentResolver();
        int initialCallLogCount = getCallLogCount(contentResolver);

        // Add an existing entry using FakeCallLogBackupAgent.writeCallToProvider
        // to simulate a call log that was already in the database.
        Call existingCall = makeCall(/* id */ 100, /* date */ 1122334455L, /* duration */
                30, /* number */ "555-0000");
        backupAgent.writeCallToProvider(existingCall);

        //  Call log count after adding the existing entry
        int callLogCountWithExistingEntry = initialCallLogCount + 1;

        // Create a new mock call
        Call call = makeCall(/* id */ 101, /* date */ 1234567890L, /* duration */ 60, /* number */
                "555-4321");

        try {
            // Restore the same call data twice using different BackupDataInput objects
            backupAgent.onRestore(
                    mockBackupDataInputWithCalls(ImmutableList.of(call)), /* appVersionCode */
                    0, /* newState */ null);
            backupAgent.onRestore(
                    mockBackupDataInputWithCalls(ImmutableList.of(call)), /* appVersionCode */
                    0, /* newState */ null);

            // Assert that only one new entry was added
            assertEquals(callLogCountWithExistingEntry + 1, getCallLogCount(contentResolver));

            // Assert that the entry matches the mock call
            assertCallCount(contentResolver, call, 1);

            // Assert that the existing entry remains in the database and is unaltered
            assertCallCount(contentResolver, existingCall, 1);
        } finally {
            clearCallLogs(contentResolver, ImmutableList.of(existingCall, call));
        }

        // Assert that the final count is equal to the initial count
        assertEquals(initialCallLogCount, getCallLogCount(contentResolver));
    }

    @Test
    @RequiresFlagsDisabled({Flags.FLAG_CALL_LOG_RESTORE_DEDUPLICATION_ENABLED})
    public void testRestore_DuplicateEntry_DeduplicationDisabled_AddsDuplicateEntry()
            throws Exception {
        FakeCallLogBackupAgent backupAgent = new FakeCallLogBackupAgent();
        backupAgent.setBackupRestoreEventLoggerProxy(mBackupRestoreEventLoggerProxy);
        backupAgent.attach(mContext);

        // Get the initial count of call log entries
        ContentResolver contentResolver = backupAgent.getContentResolver();
        int initialCallLogCount = getCallLogCount(contentResolver);

        // Add an existing entry using FakeCallLogBackupAgent.writeCallToProvider
        // to simulate a call log that was already in the database.
        Call existingCall = makeCall(/* id */ 100, /* date */ 1122334455L, /* duration */
                30, /* number */ "555-0000");
        backupAgent.writeCallToProvider(existingCall);

        //  Call log count after adding the existing entry
        int callLogCountWithExistingEntry = initialCallLogCount + 1;

        // Create a new mock call
        Call call = makeCall(/* id */ 101, /* date */ 1234567890L, /* duration */ 60, /* number */
                "555-4321");

        try {
            // Restore the same call data twice using different BackupDataInput objects
            backupAgent.onRestore(
                    mockBackupDataInputWithCalls(ImmutableList.of(call)), /* appVersionCode */
                    0, /* newState */ null);
            backupAgent.onRestore(
                    mockBackupDataInputWithCalls(ImmutableList.of(call)), /* appVersionCode */
                    0, /* newState */ null);

            // Assert that two new entries were added
            assertEquals(callLogCountWithExistingEntry + 2, getCallLogCount(contentResolver));

            // Assert that two entries exist with the same data
            assertCallCount(contentResolver, call, 2);

            // Assert that the existing entry remains in the database and is unaltered
            assertCallCount(contentResolver, existingCall, 1);
        } finally {
            clearCallLogs(contentResolver, ImmutableList.of(existingCall, call));
        }

        // Assert that the final count is equal to the initial count
        assertEquals(initialCallLogCount, getCallLogCount(contentResolver));
    }

    @Test
    public void testRestore_DifferentEntries_AddsEntries() throws Exception {
        FakeCallLogBackupAgent backupAgent = new FakeCallLogBackupAgent();
        backupAgent.setBackupRestoreEventLoggerProxy(mBackupRestoreEventLoggerProxy);
        backupAgent.attach(mContext);

        // Get the initial count of call log entries
        ContentResolver contentResolver = backupAgent.getContentResolver();
        int initialCallLogCount = getCallLogCount(contentResolver);

        // Add an existing entry using FakeCallLogBackupAgent.writeCallToProvider
        // to simulate a call log that was already in the database.
        Call existingCall = makeCall(/* id */ 100, /* date */ 1122334455L, /* duration */
                30, /* number */ "555-0000");
        backupAgent.writeCallToProvider(existingCall);

        //  Call log count after adding the existing entry
        int callLogCountWithExistingEntry = initialCallLogCount + 1;

        // Create two new mock calls
        Call call1 = makeCall(/* id */ 101, /* date */ 1234567890L, /* duration */ 60, /* number */
                "555-4321");
        Call call2 = makeCall(/* id */ 102, /* date */ 9876543210L, /* duration */ 60, /* number */
                "555-1234");
        BackupDataInput backupDataInput1 = mockBackupDataInputWithCalls(ImmutableList.of(call1));
        BackupDataInput backupDataInput2 = mockBackupDataInputWithCalls(ImmutableList.of(call2));

        try {
            // Restore the calls
            backupAgent.onRestore(backupDataInput1, /* appVersionCode */ 0, /* newState */ null);
            backupAgent.onRestore(backupDataInput2, /* appVersionCode */ 0, /* newState */ null);

            // Assert that two new entries were added
            assertEquals(callLogCountWithExistingEntry + 2, getCallLogCount(contentResolver));

            // Assert that both calls exist in the database
            assertCallCount(contentResolver, call1, 1);
            assertCallCount(contentResolver, call2, 1);

            // Assert that the existing entry remains in the database and is unaltered
            assertCallCount(contentResolver, existingCall, 1);
        } finally {
            clearCallLogs(contentResolver, ImmutableList.of(existingCall, call1, call2));
        }

        // Assert that the final count is equal to the initial count
        assertEquals(initialCallLogCount, getCallLogCount(contentResolver));
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_CALL_LOG_RESTORE_DEDUPLICATION_ENABLED,
            Flags.FLAG_BATCH_DEDUPLICATION_ENABLED})
    public void testRestore_DuplicateEntry_BatchDeduplicationEnabled_Deduplicates()
            throws Exception {
        FakeCallLogBackupAgent backupAgent = new FakeCallLogBackupAgent();
        backupAgent.setBackupRestoreEventLoggerProxy(mBackupRestoreEventLoggerProxy);
        backupAgent.attach(mContext);

        // Get the initial count of call log entries
        ContentResolver contentResolver = backupAgent.getContentResolver();
        int initialCallLogCount = getCallLogCount(contentResolver);

        // Add an existing entry using FakeCallLogBackupAgent.writeCallToProvider
        // to simulate a call log that was already in the database.
        Call existingCall = makeCall(/* id */ 100, /* date */ 1122334455L, /* duration */
                30, /* number */ "555-0000");
        backupAgent.writeCallToProvider(existingCall);

        //  Call log count after adding the existing entry
        int callLogCountWithExistingEntry = initialCallLogCount + 1;

        int testBatchSize = backupAgent.getBatchSize();
        // Create multiple new mock calls (more than the batch size)
        List<Call> calls = new ArrayList<>();
        for (int i = 0; i < testBatchSize + 2; i++) {
            calls.add(makeCall(/* id */ 101 + i, /* date */ 1234567890L + i, /* duration */
                    60 + i, /* number */ "555-4321"));
        }

        try {
            // Restore the same call data twice using different BackupDataInput objects
            backupAgent.onRestore(
                    mockBackupDataInputWithCalls(ImmutableList.copyOf(calls)), /* appVersionCode */
                    0, /* newState */ null);
            backupAgent.onRestore(
                    mockBackupDataInputWithCalls(ImmutableList.copyOf(calls)), /* appVersionCode */
                    0, /* newState */ null);

            // Assert that only the expected number of new entries were added
            assertEquals(callLogCountWithExistingEntry + calls.size(),
                    getCallLogCount(contentResolver));

            // Assert that each call exists only once
            for (Call call : calls) {
                assertCallCount(contentResolver, call, 1);
            }

            // Assert that the existing entry remains in the database and is unaltered
            assertCallCount(contentResolver, existingCall, 1);
        } finally {
            clearCallLogs(contentResolver, ImmutableList.<Call>builder()
                    .addAll(calls)
                    .add(existingCall)
                    .build());
        }

        // Assert that the final count is equal to the initial count
        assertEquals(initialCallLogCount, getCallLogCount(contentResolver));
    }

    private static void mockCursor(Cursor cursor, boolean isTelephonyComponentName) {
        when(cursor.moveToNext()).thenReturn(true).thenReturn(false);

        int CALLS_ID_COLUMN_INDEX = 1;
        int CALL_ID = 9;
        when(cursor.getColumnIndex(CallLog.Calls._ID)).thenReturn(CALLS_ID_COLUMN_INDEX);
        when(cursor.getInt(CALLS_ID_COLUMN_INDEX)).thenReturn(CALL_ID);

        int CALLS_DATE_COLUMN_INDEX = 2;
        long CALL_DATE = 20991231;
        when(cursor.getColumnIndex(CallLog.Calls.DATE)).thenReturn(CALLS_DATE_COLUMN_INDEX);
        when(cursor.getLong(CALLS_DATE_COLUMN_INDEX)).thenReturn(CALL_DATE);

        int CALLS_DURATION_COLUMN_INDEX = 3;
        long CALL_DURATION = 987654321;
        when(cursor.getColumnIndex(CallLog.Calls.DURATION)).thenReturn(
                CALLS_DURATION_COLUMN_INDEX);
        when(cursor.getLong(CALLS_DURATION_COLUMN_INDEX)).thenReturn(CALL_DURATION);

        int CALLS_NUMBER_COLUMN_INDEX = 4;
        String CALL_NUMBER = "6316056461";
        when(cursor.getColumnIndex(CallLog.Calls.NUMBER)).thenReturn(
                CALLS_NUMBER_COLUMN_INDEX);
        when(cursor.getString(CALLS_NUMBER_COLUMN_INDEX)).thenReturn(CALL_NUMBER);

        int CALLS_POST_DIAL_DIGITS_COLUMN_INDEX = 5;
        String CALL_POST_DIAL_DIGITS = "54321";
        when(cursor.getColumnIndex(CallLog.Calls.POST_DIAL_DIGITS)).thenReturn(
                CALLS_POST_DIAL_DIGITS_COLUMN_INDEX);
        when(cursor.getString(CALLS_POST_DIAL_DIGITS_COLUMN_INDEX)).thenReturn(
                CALL_POST_DIAL_DIGITS);

        int CALLS_VIA_NUMBER_COLUMN_INDEX = 6;
        String CALL_VIA_NUMBER = "via_number";
        when(cursor.getColumnIndex(CallLog.Calls.VIA_NUMBER)).thenReturn(
                CALLS_VIA_NUMBER_COLUMN_INDEX);
        when(cursor.getString(CALLS_VIA_NUMBER_COLUMN_INDEX)).thenReturn(
                CALL_VIA_NUMBER);

        int CALLS_TYPE_COLUMN_INDEX = 7;
        int CALL_TYPE = CallLog.Calls.OUTGOING_TYPE;
        when(cursor.getColumnIndex(CallLog.Calls.TYPE)).thenReturn(CALLS_TYPE_COLUMN_INDEX);
        when(cursor.getInt(CALLS_TYPE_COLUMN_INDEX)).thenReturn(CALL_TYPE);

        int CALLS_NUMBER_PRESENTATION_COLUMN_INDEX = 8;
        int CALL_NUMBER_PRESENTATION = CallLog.Calls.PRESENTATION_ALLOWED;
        when(cursor.getColumnIndex(CallLog.Calls.NUMBER_PRESENTATION)).thenReturn(
                CALLS_NUMBER_PRESENTATION_COLUMN_INDEX);
        when(cursor.getInt(CALLS_NUMBER_PRESENTATION_COLUMN_INDEX)).thenReturn(
                CALL_NUMBER_PRESENTATION);

        int CALLS_ACCOUNT_COMPONENT_NAME_COLUMN_INDEX = 9;
        String CALL_ACCOUNT_COMPONENT_NAME = "NON_TELEPHONY_COMPONENT_NAME";
        if (isTelephonyComponentName) {
            CALL_ACCOUNT_COMPONENT_NAME = TELEPHONY_COMPONENT;
        }
        when(cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)).thenReturn(
                CALLS_ACCOUNT_COMPONENT_NAME_COLUMN_INDEX);
        when(cursor.getString(CALLS_ACCOUNT_COMPONENT_NAME_COLUMN_INDEX)).thenReturn(
                CALL_ACCOUNT_COMPONENT_NAME);

        int CALLS_ACCOUNT_ID_COLUMN_INDEX = 10;
        String CALL_ACCOUNT_ID = TEST_PHONE_ACCOUNT_HANDLE_SUB_ID;
        when(cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)).thenReturn(
                CALLS_ACCOUNT_ID_COLUMN_INDEX);
        when(cursor.getString(CALLS_ACCOUNT_ID_COLUMN_INDEX)).thenReturn(
                CALL_ACCOUNT_ID);

        int CALLS_ACCOUNT_ADDRESS_COLUMN_INDEX = 11;
        String CALL_ACCOUNT_ADDRESS = "CALL_ACCOUNT_ADDRESS";
        when(cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ADDRESS)).thenReturn(
                CALLS_ACCOUNT_ADDRESS_COLUMN_INDEX);
        when(cursor.getString(CALLS_ACCOUNT_ADDRESS_COLUMN_INDEX)).thenReturn(
                CALL_ACCOUNT_ADDRESS);

        int CALLS_DATA_USAGE_COLUMN_INDEX = 12;
        long CALL_DATA_USAGE = 987654321;
        when(cursor.getColumnIndex(CallLog.Calls.DATA_USAGE)).thenReturn(
                CALLS_DATA_USAGE_COLUMN_INDEX);
        when(cursor.getLong(CALLS_DATA_USAGE_COLUMN_INDEX)).thenReturn(CALL_DATA_USAGE);

        int CALLS_FEATURES_COLUMN_INDEX = 13;
        int CALL_FEATURES = 777;
        when(cursor.getColumnIndex(CallLog.Calls.FEATURES)).thenReturn(
                CALLS_FEATURES_COLUMN_INDEX);
        when(cursor.getInt(CALLS_FEATURES_COLUMN_INDEX)).thenReturn(CALL_FEATURES);

        int CALLS_ADD_FOR_ALL_USERS_COLUMN_INDEX = 14;
        int CALL_ADD_FOR_ALL_USERS = 1;
        when(cursor.getColumnIndex(CallLog.Calls.ADD_FOR_ALL_USERS)).thenReturn(
                CALLS_ADD_FOR_ALL_USERS_COLUMN_INDEX);
        when(cursor.getInt(CALLS_ADD_FOR_ALL_USERS_COLUMN_INDEX)).thenReturn(
                CALL_ADD_FOR_ALL_USERS);

        int CALLS_BLOCK_REASON_COLUMN_INDEX = 15;
        int CALL_BLOCK_REASON = CallLog.Calls.BLOCK_REASON_NOT_BLOCKED;
        when(cursor.getColumnIndex(CallLog.Calls.BLOCK_REASON)).thenReturn(
                CALLS_BLOCK_REASON_COLUMN_INDEX);
        when(cursor.getInt(CALLS_BLOCK_REASON_COLUMN_INDEX)).thenReturn(
                CALL_BLOCK_REASON);

        int CALLS_CALL_SCREENING_APP_NAME_COLUMN_INDEX = 16;
        String CALL_CALL_SCREENING_APP_NAME = "CALL_CALL_SCREENING_APP_NAME";
        when(cursor.getColumnIndex(CallLog.Calls.CALL_SCREENING_APP_NAME)).thenReturn(
                CALLS_CALL_SCREENING_APP_NAME_COLUMN_INDEX);
        when(cursor.getString(CALLS_CALL_SCREENING_APP_NAME_COLUMN_INDEX)).thenReturn(
                CALL_CALL_SCREENING_APP_NAME);

        int CALLS_CALL_SCREENING_COMPONENT_NAME_COLUMN_INDEX = 17;
        String CALL_CALL_SCREENING_COMPONENT_NAME = "CALL_CALL_SCREENING_COMPONENT_NAME";
        when(cursor.getColumnIndex(CallLog.Calls.CALL_SCREENING_COMPONENT_NAME)).thenReturn(
                CALLS_CALL_SCREENING_COMPONENT_NAME_COLUMN_INDEX);
        when(cursor.getString(CALLS_CALL_SCREENING_COMPONENT_NAME_COLUMN_INDEX)).thenReturn(
                CALL_CALL_SCREENING_COMPONENT_NAME);

        int CALLS_MISSED_REASON_COLUMN_INDEX = 18;
        String CALL_MISSED_REASON = "CALL_MISSED_REASON";
        when(cursor.getColumnIndex(CallLog.Calls.MISSED_REASON)).thenReturn(
                CALLS_MISSED_REASON_COLUMN_INDEX);
        when(cursor.getString(CALLS_MISSED_REASON_COLUMN_INDEX)).thenReturn(
                CALL_MISSED_REASON);

        int CALLS_IS_PHONE_ACCOUNT_MIGRATION_PENDING_COLUMN_INDEX = 19;
        int CALL_IS_PHONE_ACCOUNT_MIGRATION_PENDING = 0;
        when(cursor.getColumnIndex(CallLog.Calls.IS_PHONE_ACCOUNT_MIGRATION_PENDING)).thenReturn(
                CALLS_IS_PHONE_ACCOUNT_MIGRATION_PENDING_COLUMN_INDEX);
        when(cursor.getInt(CALLS_IS_PHONE_ACCOUNT_MIGRATION_PENDING_COLUMN_INDEX)).thenReturn(
                CALL_IS_PHONE_ACCOUNT_MIGRATION_PENDING);
    }

    //sets up the mock cursor with specified column data (string) set to null
    private static void mockCursorWithNullFields(Cursor cursor, String columnToNullify) {
        when(cursor.moveToNext()).thenReturn(true).thenReturn(false);

        int CALLS_ID_COLUMN_INDEX = 1;
        int CALL_ID = 9;
        when(cursor.getColumnIndex(CallLog.Calls._ID)).thenReturn(CALLS_ID_COLUMN_INDEX);
        when(cursor.getInt(CALLS_ID_COLUMN_INDEX)).thenReturn(CALL_ID);

        int CALLS_DATE_COLUMN_INDEX = 2;
        long CALL_DATE = 20991231;
        when(cursor.getColumnIndex(CallLog.Calls.DATE)).thenReturn(CALLS_DATE_COLUMN_INDEX);
        when(cursor.getLong(CALLS_DATE_COLUMN_INDEX)).thenReturn(CALL_DATE);

        int CALLS_DURATION_COLUMN_INDEX = 3;
        long CALL_DURATION = 987654321;
        when(cursor.getColumnIndex(CallLog.Calls.DURATION)).thenReturn(
            CALLS_DURATION_COLUMN_INDEX);
        when(cursor.getLong(CALLS_DURATION_COLUMN_INDEX)).thenReturn(CALL_DURATION);

        int CALLS_NUMBER_COLUMN_INDEX = 4;
        String CALL_NUMBER = "6316056461";
        when(cursor.getColumnIndex(CallLog.Calls.NUMBER)).thenReturn(
            CALLS_NUMBER_COLUMN_INDEX);
        if (CallLog.Calls.NUMBER.equals(columnToNullify)) {
            when(cursor.getString(CALLS_NUMBER_COLUMN_INDEX)).thenReturn(null);
        } else {
            when(cursor.getString(CALLS_NUMBER_COLUMN_INDEX)).thenReturn(CALL_NUMBER);
        }

        int CALLS_POST_DIAL_DIGITS_COLUMN_INDEX = 5;
        String CALL_POST_DIAL_DIGITS = "54321";
        when(cursor.getColumnIndex(CallLog.Calls.POST_DIAL_DIGITS)).thenReturn(
            CALLS_POST_DIAL_DIGITS_COLUMN_INDEX);
        if (CallLog.Calls.POST_DIAL_DIGITS.equals(columnToNullify)) {
            when(cursor.getString(CALLS_POST_DIAL_DIGITS_COLUMN_INDEX)).thenReturn(
                null);
        } else {
            when(cursor.getString(CALLS_POST_DIAL_DIGITS_COLUMN_INDEX)).thenReturn(
                CALL_POST_DIAL_DIGITS);
        }

        int CALLS_VIA_NUMBER_COLUMN_INDEX = 6;
        String CALL_VIA_NUMBER = "via_number";
        when(cursor.getColumnIndex(CallLog.Calls.VIA_NUMBER)).thenReturn(
            CALLS_VIA_NUMBER_COLUMN_INDEX);
        if (CallLog.Calls.VIA_NUMBER.equals(columnToNullify)) {
            when(cursor.getString(CALLS_VIA_NUMBER_COLUMN_INDEX)).thenReturn(
                null);
        } else {
            when(cursor.getString(CALLS_VIA_NUMBER_COLUMN_INDEX)).thenReturn(
                CALL_VIA_NUMBER);
        }

        int CALLS_TYPE_COLUMN_INDEX = 7;
        int CALL_TYPE = CallLog.Calls.OUTGOING_TYPE;
        when(cursor.getColumnIndex(CallLog.Calls.TYPE)).thenReturn(CALLS_TYPE_COLUMN_INDEX);
        when(cursor.getInt(CALLS_TYPE_COLUMN_INDEX)).thenReturn(CALL_TYPE);

        int CALLS_NUMBER_PRESENTATION_COLUMN_INDEX = 8;
        int CALL_NUMBER_PRESENTATION = CallLog.Calls.PRESENTATION_ALLOWED;
        when(cursor.getColumnIndex(CallLog.Calls.NUMBER_PRESENTATION)).thenReturn(
            CALLS_NUMBER_PRESENTATION_COLUMN_INDEX);
        when(cursor.getInt(CALLS_NUMBER_PRESENTATION_COLUMN_INDEX)).thenReturn(
            CALL_NUMBER_PRESENTATION);

        int CALLS_ACCOUNT_COMPONENT_NAME_COLUMN_INDEX = 9;
        String CALL_ACCOUNT_COMPONENT_NAME = TELEPHONY_COMPONENT;
        when(cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)).thenReturn(
            CALLS_ACCOUNT_COMPONENT_NAME_COLUMN_INDEX);
        if (CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME.equals(columnToNullify)) {
            when(cursor.getString(CALLS_ACCOUNT_COMPONENT_NAME_COLUMN_INDEX)).thenReturn(
                null);
        } else {
            when(cursor.getString(CALLS_ACCOUNT_COMPONENT_NAME_COLUMN_INDEX)).thenReturn(
                CALL_ACCOUNT_COMPONENT_NAME);
        }

        int CALLS_ACCOUNT_ID_COLUMN_INDEX = 10;
        String CALL_ACCOUNT_ID = TEST_PHONE_ACCOUNT_HANDLE_SUB_ID;
        when(cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)).thenReturn(
            CALLS_ACCOUNT_ID_COLUMN_INDEX);
        if (CallLog.Calls.PHONE_ACCOUNT_ID.equals(columnToNullify)) {
            when(cursor.getString(CALLS_ACCOUNT_ID_COLUMN_INDEX)).thenReturn(
                null);
        } else {
            when(cursor.getString(CALLS_ACCOUNT_ID_COLUMN_INDEX)).thenReturn(
                CALL_ACCOUNT_ID);
        }

        int CALLS_ACCOUNT_ADDRESS_COLUMN_INDEX = 11;
        String CALL_ACCOUNT_ADDRESS = "CALL_ACCOUNT_ADDRESS";
        when(cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ADDRESS)).thenReturn(
            CALLS_ACCOUNT_ADDRESS_COLUMN_INDEX);
        if (CallLog.Calls.PHONE_ACCOUNT_ADDRESS.equals(columnToNullify)) {
            when(cursor.getString(CALLS_ACCOUNT_ADDRESS_COLUMN_INDEX)).thenReturn(
                null);
        } else {
            when(cursor.getString(CALLS_ACCOUNT_ADDRESS_COLUMN_INDEX)).thenReturn(
                CALL_ACCOUNT_ADDRESS);
        }

        int CALLS_DATA_USAGE_COLUMN_INDEX = 12;
        long CALL_DATA_USAGE = 987654321;
        when(cursor.getColumnIndex(CallLog.Calls.DATA_USAGE)).thenReturn(
            CALLS_DATA_USAGE_COLUMN_INDEX);
        when(cursor.getLong(CALLS_DATA_USAGE_COLUMN_INDEX)).thenReturn(CALL_DATA_USAGE);

        int CALLS_FEATURES_COLUMN_INDEX = 13;
        int CALL_FEATURES = 777;
        when(cursor.getColumnIndex(CallLog.Calls.FEATURES)).thenReturn(
            CALLS_FEATURES_COLUMN_INDEX);
        when(cursor.getInt(CALLS_FEATURES_COLUMN_INDEX)).thenReturn(CALL_FEATURES);

        int CALLS_ADD_FOR_ALL_USERS_COLUMN_INDEX = 14;
        int CALL_ADD_FOR_ALL_USERS = 1;
        when(cursor.getColumnIndex(CallLog.Calls.ADD_FOR_ALL_USERS)).thenReturn(
            CALLS_ADD_FOR_ALL_USERS_COLUMN_INDEX);
        when(cursor.getInt(CALLS_ADD_FOR_ALL_USERS_COLUMN_INDEX)).thenReturn(
            CALL_ADD_FOR_ALL_USERS);

        int CALLS_BLOCK_REASON_COLUMN_INDEX = 15;
        int CALL_BLOCK_REASON = CallLog.Calls.BLOCK_REASON_NOT_BLOCKED;
        when(cursor.getColumnIndex(CallLog.Calls.BLOCK_REASON)).thenReturn(
            CALLS_BLOCK_REASON_COLUMN_INDEX);
        when(cursor.getInt(CALLS_BLOCK_REASON_COLUMN_INDEX)).thenReturn(
            CALL_BLOCK_REASON);

        int CALLS_CALL_SCREENING_APP_NAME_COLUMN_INDEX = 16;
        String CALL_CALL_SCREENING_APP_NAME = "CALL_CALL_SCREENING_APP_NAME";
        when(cursor.getColumnIndex(CallLog.Calls.CALL_SCREENING_APP_NAME)).thenReturn(
            CALLS_CALL_SCREENING_APP_NAME_COLUMN_INDEX);
        if (CallLog.Calls.CALL_SCREENING_APP_NAME.equals(columnToNullify)) {
            when(cursor.getString(CALLS_CALL_SCREENING_APP_NAME_COLUMN_INDEX)).thenReturn(
                null);
        } else {
            when(cursor.getString(CALLS_CALL_SCREENING_APP_NAME_COLUMN_INDEX)).thenReturn(
                CALL_CALL_SCREENING_APP_NAME);
        }

        int CALLS_CALL_SCREENING_COMPONENT_NAME_COLUMN_INDEX = 17;
        String CALL_CALL_SCREENING_COMPONENT_NAME = "CALL_CALL_SCREENING_COMPONENT_NAME";
        when(cursor.getColumnIndex(CallLog.Calls.CALL_SCREENING_COMPONENT_NAME)).thenReturn(
            CALLS_CALL_SCREENING_COMPONENT_NAME_COLUMN_INDEX);
        if (CallLog.Calls.CALL_SCREENING_COMPONENT_NAME.equals(columnToNullify)) {
            when(cursor.getString(CALLS_CALL_SCREENING_COMPONENT_NAME_COLUMN_INDEX)).thenReturn(
                null);
        } else {
            when(cursor.getString(CALLS_CALL_SCREENING_COMPONENT_NAME_COLUMN_INDEX)).thenReturn(
                CALL_CALL_SCREENING_COMPONENT_NAME);
        }

        int CALLS_MISSED_REASON_COLUMN_INDEX = 18;
        String CALL_MISSED_REASON = "CALL_MISSED_REASON";
        when(cursor.getColumnIndex(CallLog.Calls.MISSED_REASON)).thenReturn(
            CALLS_MISSED_REASON_COLUMN_INDEX);
        if (CallLog.Calls.MISSED_REASON.equals(columnToNullify)) {
            when(cursor.getString(CALLS_MISSED_REASON_COLUMN_INDEX)).thenReturn(
                null);
        } else {
            when(cursor.getString(CALLS_MISSED_REASON_COLUMN_INDEX)).thenReturn(
                CALL_MISSED_REASON);
        }

        int CALLS_IS_PHONE_ACCOUNT_MIGRATION_PENDING_COLUMN_INDEX = 19;
        int CALL_IS_PHONE_ACCOUNT_MIGRATION_PENDING = 0;
        when(cursor.getColumnIndex(CallLog.Calls.IS_PHONE_ACCOUNT_MIGRATION_PENDING)).thenReturn(
            CALLS_IS_PHONE_ACCOUNT_MIGRATION_PENDING_COLUMN_INDEX);
        when(cursor.getInt(CALLS_IS_PHONE_ACCOUNT_MIGRATION_PENDING_COLUMN_INDEX)).thenReturn(
            CALL_IS_PHONE_ACCOUNT_MIGRATION_PENDING);
    }

    /**
     * Creates a mock {@link BackupDataInput} for simulating the restore of multiple call log
     * entries.
     */
    private BackupDataInput mockBackupDataInputWithCalls(List<Call> calls) throws Exception {
        BackupDataInput backupDataInput = Mockito.mock(BackupDataInput.class);

        // Array of ByteArrayOutputStream for each call
        ByteArrayOutputStream[] callByteStreams = new ByteArrayOutputStream[calls.size()];

        // Create ByteArrayOutputStreams for each call
        for (int i = 0; i < calls.size(); i++) {
            callByteStreams[i] = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(callByteStreams[i]);

            Call call = calls.get(i);
            // Intentionally keeping the version low to avoid writing
            // a lot of data not relevant to the deduplication logic.
            data.writeInt(1); // Version 1
            data.writeLong(call.date);
            data.writeLong(call.duration);
            writeString(data, call.number);
            data.writeInt(call.type);
            data.writeInt(call.numberPresentation);
            writeString(data, call.accountComponentName);
            writeString(data, call.accountId);
            writeString(data, call.accountAddress);
            data.writeLong(call.dataUsage == null ? 0 : call.dataUsage);
            data.writeInt(call.features);
            data.flush();
        }

        // Configure getDataSize
        OngoingStubbing<Integer> dataSizeStubbing = Mockito.when(backupDataInput.getDataSize());
        for (int i = 0; i < calls.size(); i++) {
            final int index = i;
            dataSizeStubbing = dataSizeStubbing.thenReturn(callByteStreams[index].size());
        }

        // Configure readEntityData
        OngoingStubbing<Integer> readStubbing = Mockito.when(
                backupDataInput.readEntityData(any(byte[].class), anyInt(), anyInt()));
        for (int i = 0; i < calls.size(); i++) {
            final int index = i;
            readStubbing = readStubbing.thenAnswer(invocation -> {
                byte[] buffer = invocation.getArgument(/* index */ 0);
                int offset = invocation.getArgument(/* index */ 1);
                System.arraycopy(callByteStreams[index].toByteArray(), 0, buffer, offset,
                        callByteStreams[index].size());
                return callByteStreams[index].size();
            });
        }

        // Configure readNextHeader
        OngoingStubbing<Boolean> hasNextStubbing = Mockito.when(backupDataInput.readNextHeader());
        for (int i = 0; i < calls.size(); i++) {
            hasNextStubbing = hasNextStubbing.thenReturn(true); // More calls to read
        }
        hasNextStubbing.thenReturn(false); // No more calls

        // Configure getKey
        OngoingStubbing<String> getKeyStubbing = Mockito.when(backupDataInput.getKey());
        for (int i = 0; i < calls.size(); i++) {
            final int index = i;
            getKeyStubbing = getKeyStubbing.thenReturn(String.valueOf(index));
        }

        return backupDataInput;
    }

    /**
     * Writes a String to a {@link DataOutputStream}, handling null values.
     */
    private void writeString(DataOutputStream data, String str) throws IOException {
        if (str == null) {
            data.writeBoolean(false);
        } else {
            data.writeBoolean(true);
            data.writeUTF(str);
        }
    }

    private static Call makeCall(int id, long date, long duration, String number) {
        Call c = new Call();
        c.id = id;
        c.date = date;
        c.duration = duration;
        c.number = number;
        c.accountComponentName = "account-component";
        c.accountId = "account-id";
        return c;
    }

    private int getCallLogCount(ContentResolver contentResolver) {
        try (Cursor cursor = contentResolver.query(CallLog.Calls.CONTENT_URI,
                /* projection */ null, /* selection */ null, /* selectionArgs */
                null, /* sortOrder */ null)) {
            return cursor != null ? cursor.getCount() : 0;
        }
    }

    private void assertCallCount(ContentResolver contentResolver, Call call,
            int expectedCount) {
        String[] whereArgs = {String.valueOf(call.date), call.number};
        try (Cursor cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, /* projection */ null,
                SELECTION_CALL_DATE_AND_NUMBER, whereArgs, /* sortOrder */ null)) {
            assertEquals(expectedCount, Objects.requireNonNull(cursor).getCount());
        }
    }

    /**
     * Clears call logs that match the given list of {@link Call}s.
     */
    private void clearCallLogs(ContentResolver contentResolver, ImmutableList<Call> callsToClear) {
        for (Call call : callsToClear) {
            String[] whereArgs = {String.valueOf(call.date), call.number};
            contentResolver.delete(CallLog.Calls.CONTENT_URI, SELECTION_CALL_DATE_AND_NUMBER,
                    whereArgs);
        }
    }

    /**
     * A fake CallLogBackupAgent used for testing. This agent simplifies
     * the insertion of call log entries for testing restore operations.
     */
    private static class FakeCallLogBackupAgent extends CallLogBackupAgent {
        private static final int TEST_BATCH_SIZE = 10;

        @Override
        protected void writeCallToProvider(Call call) {
            ContentValues values = new ContentValues();
            values.put(CallLog.Calls.NUMBER, call.number);
            values.put(CallLog.Calls.DATE, call.date);
            values.put(CallLog.Calls.DURATION, call.duration);
            values.put(CallLog.Calls.TYPE, call.type);

            ContentResolver resolver = getContentResolver();
            resolver.insert(CallLog.Calls.CONTENT_URI, values);
        }

        @Override
        @VisibleForTesting
        int getBatchSize() {
            return TEST_BATCH_SIZE;
        }
    }
}
