package com.google.audio.service;


import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.support.v4.util.Pair;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.model.BatchUpdateDocumentRequest;
import com.google.api.services.docs.v1.model.BatchUpdateDocumentResponse;
import com.google.api.services.docs.v1.model.Document;
import com.google.api.services.docs.v1.model.EndOfSegmentLocation;
import com.google.api.services.docs.v1.model.InsertTextRequest;
import com.google.api.services.docs.v1.model.Request;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import org.joda.time.DateTime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * A utility for performing read/write operations on Drive files via the REST API and opening a
 * file picker UI via Storage Access Framework.
 */
public class DocsServiceHelper {
    private final Executor mExecutor = Executors.newSingleThreadExecutor();
    private final Docs mDocsService;
    private final Drive mDriveService;

    public DocsServiceHelper(Docs docsService, Drive driveService) {
        mDocsService = docsService;
        mDriveService = driveService;
    }

    /**
     * Creates a text file in the user's My Drive folder and returns its file ID.
     */
    public Task<String> createFile(String filename) {
        return Tasks.call(mExecutor, () -> {
            Document doc = new Document()
                    .setTitle(filename);
            doc = mDocsService.documents().create(doc)
                    .execute();
            if (doc == null) {
                throw new IOException("Null result when requesting document creation.");
            }

            return doc.getDocumentId();
        });
    }

    /**
     * Opens the file identified by {@code fileId} and returns a {@link Pair} of its name and
     * contents.
     */
    public Task<String> copyDocument(String documentId) {
        return Tasks.call(mExecutor, () -> {
            String copyTitle = "copie-livre-".concat(DateTime.now().toString("d-M-Y"));
            File copyMetadata = new File().setName(copyTitle);

            File documentCopyFile =
                    mDriveService.files().copy(documentId, copyMetadata).execute();
            if (documentCopyFile == null) {
                throw new IOException("Null result when requesting document creation.");
            }

            return documentCopyFile.getId();
        });
    }

    /**
     * Updates the file identified by {@code fileId} with the given {@code name} and {@code
     * content}.
     */
    public Task<Void> saveToDocument(String documentId, String content) {
        return Tasks.call(mExecutor, () -> {

            List<Request> requests = new ArrayList<>();
            requests.add(
                    new Request().
                            setInsertText(
                                    new InsertTextRequest()
                                            .setText(content)
                                            .setEndOfSegmentLocation(
                                                    new EndOfSegmentLocation()
                                            )
                            )
            );

            BatchUpdateDocumentRequest body = new BatchUpdateDocumentRequest()
                    .setRequests(requests);
            BatchUpdateDocumentResponse response =
                    mDocsService.documents().batchUpdate(documentId, body).execute();
            return null;
        });
    }

}
