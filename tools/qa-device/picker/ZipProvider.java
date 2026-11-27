package org.rikkahub.qa.document;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
public class ZipProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return "application/zip"; }
    private File archive() { return new File(getContext().getFilesDir(), "archive.zip"); }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || !"/archive.zip".equals(uri.getPath())) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(archive(), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor result = new MatrixCursor(projection);
        MatrixCursor.RowBuilder row = result.newRow();
        for (String column : projection) {
            if (column.equals(OpenableColumns.DISPLAY_NAME)) row.add("qa-restoration.zip");
            else if (column.equals(OpenableColumns.SIZE)) row.add(archive().length());
            else row.add(null);
        }
        return result;
    }
    public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
