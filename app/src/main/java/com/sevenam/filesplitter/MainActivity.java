package com.sevenam.filesplitter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_TREE = 1001;

    private Button btnChooseFolder;
    private Button btnStart;
    private EditText inputBatchSize;
    private TextView txtSelectedFolder;
    private TextView txtFileCount;
    private TextView txtProgress;
    private ProgressBar progressBar;

    private Uri selectedTreeUri;
    private String selectedFolderName = "Thư mục";
    private volatile boolean working = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnChooseFolder = findViewById(R.id.btnChooseFolder);
        btnStart = findViewById(R.id.btnStart);
        inputBatchSize = findViewById(R.id.inputBatchSize);
        txtSelectedFolder = findViewById(R.id.txtSelectedFolder);
        txtFileCount = findViewById(R.id.txtFileCount);
        txtProgress = findViewById(R.id.txtProgress);
        progressBar = findViewById(R.id.progressBar);

        btnChooseFolder.setOnClickListener(v -> chooseFolder());
        btnStart.setOnClickListener(v -> confirmAndStart());
    }

    private void chooseFolder() {
        if (working) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_OPEN_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN_TREE || resultCode != RESULT_OK || data == null) return;

        Uri uri = data.getData();
        if (uri == null) return;

        int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) {
        }

        selectedTreeUri = uri;
        selectedFolderName = queryDisplayName(uri);
        if (selectedFolderName == null || selectedFolderName.trim().isEmpty()) {
            selectedFolderName = "Thư mục";
        }
        txtSelectedFolder.setText("Đã chọn: " + selectedFolderName);
        txtProgress.setText("Đang đếm file...");
        btnStart.setEnabled(false);

        new Thread(() -> {
            try {
                int count = listDirectFiles(selectedTreeUri).size();
                runOnUiThread(() -> {
                    txtFileCount.setText("Số file: " + count);
                    txtProgress.setText(count > 0 ? "Sẵn sàng" : "Thư mục không có file trực tiếp để tách");
                    btnStart.setEnabled(count > 0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("Không thể đọc thư mục: " + safeMessage(e)));
            }
        }).start();
    }

    private void confirmAndStart() {
        if (selectedTreeUri == null || working) return;

        int batchSize;
        try {
            batchSize = Integer.parseInt(inputBatchSize.getText().toString().trim());
        } catch (Exception e) {
            batchSize = 500;
        }
        if (batchSize < 1) {
            Toast.makeText(this, "Số file mỗi thư mục phải lớn hơn 0", Toast.LENGTH_SHORT).show();
            return;
        }

        final int finalBatchSize = batchSize;
        new AlertDialog.Builder(this)
                .setTitle("Bắt đầu tách file?")
                .setMessage("Các file sẽ được DI CHUYỂN vào các thư mục \"" + selectedFolderName + " - P1\", \"P2\"...\n\nTối đa " + finalBatchSize + " file mỗi thư mục.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Bắt đầu", (dialog, which) -> startSplitting(finalBatchSize))
                .show();
    }

    private void startSplitting(int batchSize) {
        working = true;
        btnChooseFolder.setEnabled(false);
        btnStart.setEnabled(false);
        inputBatchSize.setEnabled(false);
        progressBar.setProgress(0);
        txtProgress.setText("Đang chuẩn bị...");

        new Thread(() -> {
            int moved = 0;
            int failed = 0;
            try {
                List<DocItem> files = listDirectFiles(selectedTreeUri);
                Collections.sort(files, Comparator.comparing(a -> a.name.toLowerCase()));
                final int total = files.size();

                if (total == 0) {
                    finishWork("Không có file trực tiếp để tách.", 0, 0, 0);
                    return;
                }

                Uri sourceParent = getTreeDocumentUri(selectedTreeUri);
                int part = 1;
                int inCurrentPart = 0;
                Uri targetFolder = null;

                for (int i = 0; i < total; i++) {
                    if (targetFolder == null || inCurrentPart >= batchSize) {
                        String partName = selectedFolderName + " - P" + part;
                        targetFolder = findOrCreateDirectory(selectedTreeUri, partName);
                        if (targetFolder == null) {
                            throw new Exception("Không thể tạo thư mục " + partName);
                        }
                        inCurrentPart = countDirectFiles(targetFolder);
                        if (inCurrentPart >= batchSize) {
                            part++;
                            targetFolder = null;
                            i--;
                            continue;
                        }
                    }

                    DocItem item = files.get(i);
                    boolean ok = moveWithFallback(item.uri, sourceParent, targetFolder, item.name, item.mimeType);
                    if (ok) {
                        moved++;
                        inCurrentPart++;
                    } else {
                        failed++;
                    }

                    final int done = i + 1;
                    final int movedNow = moved;
                    final int failedNow = failed;
                    final int progress = Math.round(done * 100f / total);
                    runOnUiThread(() -> {
                        progressBar.setProgress(progress);
                        txtProgress.setText("Đang xử lý " + done + " / " + total + " • Thành công: " + movedNow + (failedNow > 0 ? " • Lỗi: " + failedNow : ""));
                    });

                    if (inCurrentPart >= batchSize) {
                        part++;
                        targetFolder = null;
                    }
                }

                finishWork("Hoàn tất", total, moved, failed);
            } catch (Exception e) {
                final int movedFinal = moved;
                final int failedFinal = failed;
                runOnUiThread(() -> {
                    showError("Có lỗi khi xử lý: " + safeMessage(e) + "\n\nĐã di chuyển: " + movedFinal + " file" + (failedFinal > 0 ? "\nLỗi: " + failedFinal + " file" : ""));
                    resetControls();
                });
            }
        }).start();
    }

    private void finishWork(String title, int total, int moved, int failed) {
        runOnUiThread(() -> {
            progressBar.setProgress(100);
            txtProgress.setText("Hoàn tất • Đã di chuyển " + moved + " / " + total + " file" + (failed > 0 ? " • Lỗi: " + failed : ""));
            txtFileCount.setText("Số file còn lại ở thư mục gốc: 0");
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("Đã di chuyển: " + moved + " file" + (failed > 0 ? "\nKhông xử lý được: " + failed + " file" : ""))
                    .setPositiveButton("OK", null)
                    .show();
            resetControls();
            refreshCount();
        });
    }

    private void refreshCount() {
        if (selectedTreeUri == null) return;
        new Thread(() -> {
            try {
                int count = listDirectFiles(selectedTreeUri).size();
                runOnUiThread(() -> {
                    txtFileCount.setText("Số file: " + count);
                    btnStart.setEnabled(count > 0 && !working);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void resetControls() {
        working = false;
        btnChooseFolder.setEnabled(true);
        inputBatchSize.setEnabled(true);
        btnStart.setEnabled(selectedTreeUri != null);
    }

    private Uri getTreeDocumentUri(Uri treeUri) {
        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }

    private List<DocItem> listDirectFiles(Uri treeUri) throws Exception {
        String parentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        return queryFiles(childrenUri, treeUri);
    }

    private int countDirectFiles(Uri directoryDocumentUri) throws Exception {
        String docId = DocumentsContract.getDocumentId(directoryDocumentUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(selectedTreeUri, docId);
        return queryFiles(childrenUri, selectedTreeUri).size();
    }

    private List<DocItem> queryFiles(Uri childrenUri, Uri treeForUris) throws Exception {
        List<DocItem> result = new ArrayList<>();
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) return result;
            int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idCol);
                String name = cursor.getString(nameCol);
                String mime = cursor.getString(mimeCol);
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeForUris, id);
                    result.add(new DocItem(docUri, name == null ? "file" : name, mime));
                }
            }
        }
        return result;
    }

    private Uri findOrCreateDirectory(Uri treeUri, String name) throws Exception {
        String parentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                while (cursor.moveToNext()) {
                    String existingName = cursor.getString(nameCol);
                    String mime = cursor.getString(mimeCol);
                    if (name.equals(existingName) && DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol));
                    }
                }
            }
        }

        Uri parentUri = getTreeDocumentUri(treeUri);
        return DocumentsContract.createDocument(getContentResolver(), parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name);
    }

    private boolean moveWithFallback(Uri source, Uri sourceParent, Uri targetParent, String name, String mime) {
        ContentResolver resolver = getContentResolver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Uri moved = DocumentsContract.moveDocument(resolver, source, sourceParent, targetParent);
                if (moved != null) return true;
            } catch (Exception ignored) {
            }
        }

        Uri target = null;
        try {
            String safeMime = (mime == null || mime.trim().isEmpty()) ? "application/octet-stream" : mime;
            target = DocumentsContract.createDocument(resolver, targetParent, safeMime, name);
            if (target == null) return false;

            try (InputStream in = resolver.openInputStream(source);
                 OutputStream out = resolver.openOutputStream(target, "w")) {
                if (in == null || out == null) throw new Exception("Không mở được luồng dữ liệu");
                byte[] buffer = new byte[1024 * 64];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            if (!DocumentsContract.deleteDocument(resolver, source)) {
                try { DocumentsContract.deleteDocument(resolver, target); } catch (Exception ignored) {}
                return false;
            }
            return true;
        } catch (Exception e) {
            if (target != null) {
                try { DocumentsContract.deleteDocument(resolver, target); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    private String queryDisplayName(Uri uri) {
        String[] projection = { DocumentsContract.Document.COLUMN_DISPLAY_NAME };
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        try {
            String id = DocumentsContract.getTreeDocumentId(uri);
            int p = id.lastIndexOf(':');
            return p >= 0 ? id.substring(p + 1) : id;
        } catch (Exception ignored) {
            return "Thư mục";
        }
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setTitle("Lỗi")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private static class DocItem {
        final Uri uri;
        final String name;
        final String mimeType;

        DocItem(Uri uri, String name, String mimeType) {
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
        }
    }
}
