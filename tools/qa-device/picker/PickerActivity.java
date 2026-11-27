package org.rikkahub.qa.document;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
public class PickerActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Button file = new Button(this);
        file.setText("Open synthetic recovery ZIP");
        file.setOnClickListener(v -> {
            if (!"me.rerere.rikkahub.personal".equals(getCallingPackage())) { finish(); return; }
            Uri archive = Uri.parse("content://org.rikkahub.qa.document/archive.zip");
            Intent result = new Intent().setData(archive)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            result.setClipData(ClipData.newRawUri("Synthetic backup ZIP", archive));
            setResult(RESULT_OK, result);
            finish();
        });
        setContentView(file);
    }
}
