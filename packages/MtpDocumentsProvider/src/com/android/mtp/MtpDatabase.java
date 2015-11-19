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

package com.android.mtp;

import static com.android.mtp.MtpDatabaseConstants.*;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.MediaFile;
import android.mtp.MtpConstants;
import android.mtp.MtpObjectInfo;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;

/**
 * Database for MTP objects.
 * The object handle which is identifier for object in MTP protocol is not stable over sessions.
 * When we resume the process, we need to remap our document ID with MTP's object handle.
 *
 * If the remote MTP device is backed by typical file system, the file name
 * is unique among files in a directory. However, MTP protocol itself does
 * not guarantee the uniqueness of name so we cannot use fullpath as ID.
 *
 * Instead of fullpath, we use artificial ID generated by MtpDatabase itself. The database object
 * remembers the map of document ID and object handle, and remaps new object handle with document ID
 * by comparing the directory structure and object name.
 *
 * To start putting documents into the database, the client needs to call
 * {@link #startAddingChildDocuments(String)} with the parent document ID. Also it needs to call
 * {@link #stopAddingChildDocuments(String)} after putting all child documents to the database.
 * (All explanations are same for root documents)
 *
 * database.startAddingChildDocuments();
 * database.putChildDocuments();
 * database.stopAddingChildDocuments();
 *
 * To update the existing documents, the client code can repeat to call the three methods again.
 * The newly added rows update corresponding existing rows that have same MTP identifier like
 * objectHandle.
 *
 * The client can call putChildDocuments multiple times to add documents by chunk, but it needs to
 * put all documents under the parent before calling stopAddingChildDocuments. Otherwise missing
 * documents are regarded as deleted, and will be removed from the database.
 *
 * If the client calls clearMtpIdentifier(), it clears MTP identifier in the database. In this case,
 * the database tries to find corresponding rows by using document's name instead of MTP identifier
 * at the next update cycle.
 *
 * TODO: Improve performance by SQL optimization.
 */
class MtpDatabase {
    private final MtpDatabaseInternal mDatabase;

    /**
     * Mapping mode for roots/documents where we start adding child documents.
     * Methods operate the state needs to be synchronized.
     */
    private final Map<String, Integer> mMappingMode = new HashMap<>();

    @VisibleForTesting
    MtpDatabase(Context context, int flags) {
        mDatabase = new MtpDatabaseInternal(context, flags);
    }

    /**
     * Closes the database.
     */
    @VisibleForTesting
    void close() {
        mDatabase.close();
    }

    /**
     * {@link MtpDatabaseInternal#queryRoots}
     */
    Cursor queryRoots(String[] columnNames) {
        return mDatabase.queryRoots(columnNames);
    }

    /**
     * {@link MtpDatabaseInternal#queryRootDocuments}
     */
    @VisibleForTesting
    Cursor queryRootDocuments(String[] columnNames) {
        return mDatabase.queryRootDocuments(columnNames);
    }

    /**
     * {@link MtpDatabaseInternal#queryChildDocuments}
     */
    Cursor queryChildDocuments(String[] columnNames, String parentDocumentId) {
        return mDatabase.queryChildDocuments(columnNames, parentDocumentId);
    }

    /**
     * {@link MtpDatabaseInternal#queryDocument}
     */
    Cursor queryDocument(String documentId, String[] projection) {
        return mDatabase.queryDocument(documentId, projection);
    }

    /**
     * {@link MtpDatabaseInternal#createIdentifier}
     */
    Identifier createIdentifier(String parentDocumentId) throws FileNotFoundException {
        return mDatabase.createIdentifier(parentDocumentId);
    }

    /**
     * {@link MtpDatabaseInternal#removeDeviceRows}
     */
    void removeDeviceRows(int deviceId) {
        mDatabase.removeDeviceRows(deviceId);
    }

    /**
     * {@link MtpDatabaseInternal#getParentId}
     * @throws FileNotFoundException
     */
    String getParentId(String documentId) throws FileNotFoundException {
        return mDatabase.getParentId(documentId);
    }

    /**
     * {@link MtpDatabaseInternal#deleteDocument}
     */
    void deleteDocument(String documentId) {
        mDatabase.deleteDocument(documentId);
    }

    /**
     * {@link MtpDatabaseInternal#putNewDocument}
     * @throws FileNotFoundException
     */
    String putNewDocument(int deviceId, String parentDocumentId, MtpObjectInfo info)
            throws FileNotFoundException {
        final ContentValues values = new ContentValues();
        getChildDocumentValues(values, deviceId, parentDocumentId, info);
        return mDatabase.putNewDocument(parentDocumentId, values);
    }

    /**
     * Invokes {@link MtpDatabaseInternal#startAddingDocuments} for root documents.
     * @param deviceId Device ID.
     */
    synchronized void startAddingRootDocuments(int deviceId) {
        final String mappingStateKey = getRootDocumentsMappingStateKey(deviceId);
        if (mMappingMode.containsKey(mappingStateKey)) {
            throw new Error("Mapping for the root has already started.");
        }
        mMappingMode.put(
                mappingStateKey,
                mDatabase.startAddingDocuments(
                        SELECTION_ROOT_DOCUMENTS, Integer.toString(deviceId)));
    }

    /**
     * Invokes {@link MtpDatabaseInternal#startAddingDocuments} for child of specific documents.
     * @param parentDocumentId Document ID for parent document.
     */
    @VisibleForTesting
    synchronized void startAddingChildDocuments(String parentDocumentId) {
        final String mappingStateKey = getChildDocumentsMappingStateKey(parentDocumentId);
        if (mMappingMode.containsKey(mappingStateKey)) {
            throw new Error("Mapping for the root has already started.");
        }
        mMappingMode.put(
                mappingStateKey,
                mDatabase.startAddingDocuments(SELECTION_CHILD_DOCUMENTS, parentDocumentId));
    }

    /**
     * Puts root information to database.
     * @param deviceId Device ID
     * @param resources Resources required to localize root name.
     * @param roots List of root information.
     * @return If roots are added or removed from the database.
     */
    synchronized boolean putRootDocuments(int deviceId, Resources resources, MtpRoot[] roots) {
        mDatabase.beginTransaction();
        try {
            final boolean heuristic;
            final String mapColumn;
            final String key = getRootDocumentsMappingStateKey(deviceId);
            if (!mMappingMode.containsKey(key)) {
                throw new IllegalStateException("startAddingRootDocuments has not been called.");
            }
            switch (mMappingMode.get(key)) {
                case MAP_BY_MTP_IDENTIFIER:
                    heuristic = false;
                    mapColumn = COLUMN_STORAGE_ID;
                    break;
                case MAP_BY_NAME:
                    heuristic = true;
                    mapColumn = Document.COLUMN_DISPLAY_NAME;
                    break;
                default:
                    throw new Error("Unexpected map mode.");
            }
            final ContentValues[] valuesList = new ContentValues[roots.length];
            for (int i = 0; i < roots.length; i++) {
                if (roots[i].mDeviceId != deviceId) {
                    throw new IllegalArgumentException();
                }
                valuesList[i] = new ContentValues();
                getRootDocumentValues(valuesList[i], resources, roots[i]);
            }
            final boolean changed = mDatabase.putDocuments(
                    valuesList,
                    SELECTION_ROOT_DOCUMENTS,
                    Integer.toString(deviceId),
                    heuristic,
                    mapColumn);
            final ContentValues values = new ContentValues();
            int i = 0;
            for (final MtpRoot root : roots) {
                // Use the same value for the root ID and the corresponding document ID.
                final String documentId = valuesList[i++].getAsString(Document.COLUMN_DOCUMENT_ID);
                // If it fails to insert/update documents, the document ID will be set with -1.
                // In this case we don't insert/update root extra information neither.
                if (documentId == null) {
                    continue;
                }
                values.put(Root.COLUMN_ROOT_ID, documentId);
                values.put(
                        Root.COLUMN_FLAGS,
                        Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE);
                values.put(Root.COLUMN_AVAILABLE_BYTES, root.mFreeSpace);
                values.put(Root.COLUMN_CAPACITY_BYTES, root.mMaxCapacity);
                values.put(Root.COLUMN_MIME_TYPES, "");
                mDatabase.putRootExtra(values);
            }
            mDatabase.setTransactionSuccessful();
            return changed;
        } finally {
            mDatabase.endTransaction();
        }
    }

    /**
     * Puts document information to database.
     * @param deviceId Device ID
     * @param parentId Parent document ID.
     * @param documents List of document information.
     */
    @VisibleForTesting
    synchronized void putChildDocuments(int deviceId, String parentId, MtpObjectInfo[] documents) {
        final boolean heuristic;
        final String mapColumn;
        switch (mMappingMode.get(getChildDocumentsMappingStateKey(parentId))) {
            case MAP_BY_MTP_IDENTIFIER:
                heuristic = false;
                mapColumn = COLUMN_OBJECT_HANDLE;
                break;
            case MAP_BY_NAME:
                heuristic = true;
                mapColumn = Document.COLUMN_DISPLAY_NAME;
                break;
            default:
                throw new Error("Unexpected map mode.");
        }
        final ContentValues[] valuesList = new ContentValues[documents.length];
        for (int i = 0; i < documents.length; i++) {
            valuesList[i] = new ContentValues();
            getChildDocumentValues(valuesList[i], deviceId, parentId, documents[i]);
        }
        mDatabase.putDocuments(
                valuesList, SELECTION_CHILD_DOCUMENTS, parentId, heuristic, mapColumn);
    }

    /**
     * Clears mapping between MTP identifier and document/root ID.
     */
    @VisibleForTesting
    synchronized void clearMapping() {
        mDatabase.clearMapping();
        mMappingMode.clear();
    }

    /**
     * Stops adding root documents.
     * @param deviceId Device ID.
     * @return True if new rows are added/removed.
     */
    synchronized boolean stopAddingRootDocuments(int deviceId) {
        final String mappingModeKey = getRootDocumentsMappingStateKey(deviceId);
        switch (mMappingMode.get(mappingModeKey)) {
            case MAP_BY_MTP_IDENTIFIER:
                mMappingMode.remove(mappingModeKey);
                return mDatabase.stopAddingDocuments(
                        SELECTION_ROOT_DOCUMENTS,
                        Integer.toString(deviceId),
                        COLUMN_STORAGE_ID);
            case MAP_BY_NAME:
                mMappingMode.remove(mappingModeKey);
                return mDatabase.stopAddingDocuments(
                        SELECTION_ROOT_DOCUMENTS,
                        Integer.toString(deviceId),
                        Document.COLUMN_DISPLAY_NAME);
            default:
                throw new Error("Unexpected mapping state.");
        }
    }

    /**
     * Stops adding documents under the parent.
     * @param parentId Document ID of the parent.
     */
    @VisibleForTesting
    synchronized void stopAddingChildDocuments(String parentId) {
        final String mappingModeKey = getChildDocumentsMappingStateKey(parentId);
        switch (mMappingMode.get(mappingModeKey)) {
            case MAP_BY_MTP_IDENTIFIER:
                mDatabase.stopAddingDocuments(
                        SELECTION_CHILD_DOCUMENTS,
                        parentId,
                        COLUMN_OBJECT_HANDLE);
                break;
            case MAP_BY_NAME:
                mDatabase.stopAddingDocuments(
                        SELECTION_CHILD_DOCUMENTS,
                        parentId,
                        Document.COLUMN_DISPLAY_NAME);
                break;
            default:
                throw new Error("Unexpected mapping state.");
        }
        mMappingMode.remove(mappingModeKey);
    }

    /**
     * Gets {@link ContentValues} for the given root.
     * @param values {@link ContentValues} that receives values.
     * @param resources Resources used to get localized root name.
     * @param root Root to be converted {@link ContentValues}.
     */
    private static void getRootDocumentValues(
            ContentValues values, Resources resources, MtpRoot root) {
        values.clear();
        values.put(COLUMN_DEVICE_ID, root.mDeviceId);
        values.put(COLUMN_STORAGE_ID, root.mStorageId);
        values.putNull(COLUMN_OBJECT_HANDLE);
        values.putNull(COLUMN_PARENT_DOCUMENT_ID);
        values.put(COLUMN_ROW_STATE, ROW_STATE_VALID);
        values.put(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        values.put(Document.COLUMN_DISPLAY_NAME, root.getRootName(resources));
        values.putNull(Document.COLUMN_SUMMARY);
        values.putNull(Document.COLUMN_LAST_MODIFIED);
        values.putNull(Document.COLUMN_ICON);
        values.put(Document.COLUMN_FLAGS, 0);
        values.put(Document.COLUMN_SIZE,
                (int) Math.min(root.mMaxCapacity - root.mFreeSpace, Integer.MAX_VALUE));
    }

    /**
     * Gets {@link ContentValues} for the given MTP object.
     * @param values {@link ContentValues} that receives values.
     * @param deviceId Device ID of the object.
     * @param parentId Parent document ID of the object.
     * @param info MTP object info.
     */
    private void getChildDocumentValues(
            ContentValues values, int deviceId, String parentId, MtpObjectInfo info) {
        values.clear();
        final String mimeType = info.getFormat() == MtpConstants.FORMAT_ASSOCIATION ?
                DocumentsContract.Document.MIME_TYPE_DIR :
                MediaFile.getMimeTypeForFormatCode(info.getFormat());
        int flag = 0;
        if (info.getProtectionStatus() == 0) {
            flag |= Document.FLAG_SUPPORTS_DELETE |
                    Document.FLAG_SUPPORTS_WRITE;
            if (mimeType == Document.MIME_TYPE_DIR) {
                flag |= Document.FLAG_DIR_SUPPORTS_CREATE;
            }
        }
        if (info.getThumbCompressedSize() > 0) {
            flag |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }
        values.put(COLUMN_DEVICE_ID, deviceId);
        values.put(COLUMN_STORAGE_ID, info.getStorageId());
        values.put(COLUMN_OBJECT_HANDLE, info.getObjectHandle());
        values.put(COLUMN_PARENT_DOCUMENT_ID, parentId);
        values.put(COLUMN_ROW_STATE, ROW_STATE_VALID);
        values.put(Document.COLUMN_MIME_TYPE, mimeType);
        values.put(Document.COLUMN_DISPLAY_NAME, info.getName());
        values.putNull(Document.COLUMN_SUMMARY);
        values.put(
                Document.COLUMN_LAST_MODIFIED,
                info.getDateModified() != 0 ? info.getDateModified() : null);
        values.putNull(Document.COLUMN_ICON);
        values.put(Document.COLUMN_FLAGS, flag);
        values.put(Document.COLUMN_SIZE, info.getCompressedSize());
    }

    /**
     * @param deviceId Device ID.
     * @return Key for {@link #mMappingMode}.
     */
    private static String getRootDocumentsMappingStateKey(int deviceId) {
        return "RootDocuments/" + deviceId;
    }

    /**
     * @param parentDocumentId Document ID for the parent document.
     * @return Key for {@link #mMappingMode}.
     */
    private static String getChildDocumentsMappingStateKey(String parentDocumentId) {
        return "ChildDocuments/" + parentDocumentId;
    }
}
