package dk.compute.dtu.hrv.storage;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.util.Log;
import android.os.Handler;

public class SimpleStorageWorker implements Handler.Callback {
	String TAG = this.getClass().getSimpleName();
	private File hr_file;
	private File rr_file;
    private Writer hr_writer = null;
    private Writer rr_writer = null;

    private Context _context;
    private boolean _writable = false;
    public static final int MSG_OPEN = 0;
    public static final int MSG_WRITE = 1;
    public static final int MSG_CLOSE = 2;
    
    public SimpleStorageWorker(Context context) throws Exception{
    	_context = context;
    }
    
	@Override
	public boolean handleMessage(Message msg) {
        Bundle b = msg.getData();
		switch(msg.what) {
            case MSG_OPEN:
                String prefix = b.getString("Prefix");

                rr_file = newFile("rr", prefix);
                rr_writer = newWriter(rr_file);
                hr_file = newFile("hr", prefix);
                hr_writer = newWriter(hr_file);
                break;
            case MSG_WRITE:
                store(b.getInt("heart_rate"), b.getIntArray("rr"), b.getLong("timestamp"));
                break;
            case MSG_CLOSE:
                close(rr_file, rr_writer);
                close(hr_file, hr_writer);
                break;
            default:
                break;
		}
			
		return false;
	}

	public void store(int heart_rate, int[] rr, long timestamp) {
		if (_writable){
				write(heart_rate, timestamp, hr_writer);
				write(rr, timestamp, rr_writer);
		}
	}
	
	public File newFile(String prefix, String deviceAddress){
			if (isExternalStorageWritable()){
				String currentDateandTime = new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.GERMANY).format(new Date());
				File f = new File(Environment.getExternalStorageDirectory(), "HRV");
                if (f.mkdirs() || f.isDirectory()) {
                    f = new File(f, prefix + "_" + deviceAddress.replace(":", "") + "_" + currentDateandTime + ".csv");
                    Log.d(TAG, "Opening new file: " + f.getAbsolutePath());
                }
                return f;
			} else {
                Log.d(TAG, "External storage not writable");
            }
        return null;
	}

    public Writer newWriter(File file){
        if (file != null) {
            try {
                Log.d(TAG, "Creating Writer for " + file.getAbsoluteFile());
                Writer writer = new BufferedWriter(new FileWriter(file, false));
                _writable = true;
                return writer;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
        return null;
    }

	public void write(int[] data, long timestamp, Writer writer){
		if (_writable){
            if (writer != null) {
                try {
                    //Log.d(TAG, "Saving data to: " + file.getAbsolutePath());
                    for (int d : data) {
                        writer.write(String.format("%d", timestamp));
                        writer.write(String.format(";%d", d));
                        writer.write("\n");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                Log.d(TAG, "Writer is null");
            }
		}
	}

    public void write(int data, long timestamp, Writer writer){
        if (_writable){
            if (writer != null) {
                try {
                    writer.write(String.format("%d", timestamp));
                    writer.write(String.format(";%d", data));
                    writer.write("\n");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                Log.d(TAG, "Writer is null");
            }
        }
    }
	
	public void close(File file, Writer writer){
        try {
            Log.d(TAG, "Closing " + file.getAbsoluteFile());
            writer.flush();
            writer.close();
            _writable = false;
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (file != null){ // File never opened
            _context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
		}
	}
	
	public boolean writable(){
		return _writable;
	}
	
    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        Log.e("SimpleStorage", "External storage is not writable");
        return false;
    }
}
