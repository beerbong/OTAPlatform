/*
 * Copyright (C) 2013 OTAPlatform
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

package com.beerbong.otaplatform.manager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask.Status;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;

import com.beerbong.otaplatform.DownloadService;
import com.beerbong.otaplatform.R;
import com.beerbong.otaplatform.updater.CancelPackage;
import com.beerbong.otaplatform.updater.TWRPUpdater;
import com.beerbong.otaplatform.updater.Updater;
import com.beerbong.otaplatform.updater.Updater.PackageInfo;
import com.beerbong.otaplatform.util.Constants;
import com.beerbong.otaplatform.util.DownloadTask.DownloadStatus;
import com.beerbong.otaplatform.util.FileItem;

public class FileManager extends Manager {

    private List<FileItem> mItems;
    private String mInternalStoragePath;
    private String mExternalStoragePath;
    private int mSelectedBackup = -1;

    protected FileManager(Context context) {
        super(context);

        calculateItems();
        readMounts();
    }

    public double getSpaceLeft() {
        StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
        double sdAvailSize = (double) stat.getAvailableBlocks() * (double) stat.getBlockSize();
        // One binary gigabyte equals 1,073,741,824 bytes.
        return sdAvailSize / 1073741824;
    }

    public String getInternalStoragePath() {
        return mInternalStoragePath;
    }

    public String getExternalStoragePath() {
        return mExternalStoragePath;
    }

    public void removeItem(FileItem item) {
        mItems.remove(item);
        ManagerFactory.getPreferencesManager(mContext).removeFlashQueue(item.toString());
    }

    public List<FileItem> getFileItems() {
        if (mItems.size() == 0) {
            calculateItems();
        }
        return mItems;
    }

    public void clearItems() {
        mItems.clear();
    }

    private void calculateItems() {
        String[] queue = ManagerFactory.getPreferencesManager(mContext).getFlashQueue();
        mItems = new ArrayList<FileItem>();
        for (String q : queue) {
            FileItem item = new FileItem(q);
            File file = new File(item.getPath());
            if (file.exists()) {
                mItems.add(item);
            }
        }
    }

    public Updater.PackageInfo onNewIntent(Context context, Intent intent) {
        DownloadService.FileInfo fileInfo = (DownloadService.FileInfo) (intent.getExtras() == null ? null
                : intent.getExtras().get(Constants.FILE_INFO));
        int notificationId = fileInfo.notificationId;
        if (notificationId == Constants.NEWROMVERSION_NOTIFICATION_ID
                || notificationId == Constants.NEWGAPPSVERSION_NOTIFICATION_ID) {
            PackageInfo info = fileInfo.packageInfo;

            NotificationManager nMgr = (NotificationManager) context
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            nMgr.cancel(notificationId);

            if (notificationId == Constants.NEWROMVERSION_NOTIFICATION_ID) {
                notificationId = Constants.DOWNLOADROM_NOTIFICATION_ID;
            } else {
                notificationId = Constants.DOWNLOADGAPPS_NOTIFICATION_ID;
            }
            return info;
        } else if (notificationId == Constants.DOWNLOADROM_NOTIFICATION_ID
                || notificationId == Constants.DOWNLOADGAPPS_NOTIFICATION_ID
                || notificationId == Constants.DOWNLOADTWRP_NOTIFICATION_ID) {
            switch (notificationId) {
                case Constants.DOWNLOADROM_NOTIFICATION_ID:
                case Constants.DOWNLOADGAPPS_NOTIFICATION_ID:
                    if (fileInfo.status == Status.FINISHED && fileInfo.downloadStatus == DownloadStatus.FINISHED) {
                        if (addItem(fileInfo.path)) {
                            Toast.makeText(context, R.string.install_file_manager_zip_added,
                                    Toast.LENGTH_LONG).show();
                        }
                        NotificationManager nMgr = (NotificationManager) context
                                .getSystemService(Context.NOTIFICATION_SERVICE);
                        nMgr.cancel(notificationId);
                        return null;
                    }
                    break;
                case Constants.DOWNLOADTWRP_NOTIFICATION_ID:
                    if (fileInfo.status == Status.FINISHED && fileInfo.downloadStatus == DownloadStatus.FINISHED) {
                        new TWRPUpdater(context, null).installTWRP(fileInfo.file, fileInfo.md5);
                    }
                    break;
            }
            if (fileInfo.status != Status.FINISHED && fileInfo.downloadStatus != DownloadStatus.FINISHED) {
                cancelDownload(context, notificationId, fileInfo);
                return new CancelPackage();
            }
        }
        return null;
    }

    public boolean addItem(String filePath) {

        if (filePath == null || !filePath.endsWith(".zip")) {
            Toast.makeText(mContext, R.string.install_file_manager_invalid_zip, Toast.LENGTH_SHORT)
                    .show();
            return false;
        }

        PreferencesManager pManager = ManagerFactory.getPreferencesManager(mContext);

        String sdcardPath = new String(filePath);

        String internalStorage = pManager.getInternalStorage();
        String externalStorage = pManager.getExternalStorage();

        String[] internalNames = new String[] { mInternalStoragePath, "/mnt/sdcard", "/sdcard" };
        String[] externalNames = new String[] {
                mExternalStoragePath == null ? " " : mExternalStoragePath,
                "/mnt/extSdCard",
                "/extSdCard" };
        for (int i = 0; i < internalNames.length; i++) {
            String internalName = internalNames[i];
            String externalName = externalNames[i];
            boolean external = isExternalStorage(filePath);
            if (external) {
                if (filePath.startsWith(externalName)) {
                    filePath = filePath.replace(externalName, "/" + externalStorage);
                }
            } else {
                if (filePath.startsWith(internalName)) {
                    filePath = filePath.replace(internalName, "/" + internalStorage);
                }
            }
        }

        File file = new File(sdcardPath);
        if (!file.exists()) {
            Toast.makeText(mContext, R.string.install_file_manager_not_found_zip, Toast.LENGTH_LONG)
                    .show();
            return false;
        } else {

            for (FileItem item : mItems) {
                if (item.getKey().equals(filePath)) {
                    mItems.remove(item);
                    break;
                }
            }

            FileItem item = new FileItem(filePath,
                    sdcardPath.substring(sdcardPath.lastIndexOf("/") + 1), sdcardPath);

            mItems.add(item);

            pManager.addFlashQueue(item.toString());
        }
        return true;
    }

    public void showDeleteDialog(final Context context) {

        AlertDialog.Builder alert = new AlertDialog.Builder(context);
        alert.setTitle(R.string.alert_delete_title);

        final String backupFolder = ManagerFactory.getRecoveryManager(context).getBackupDir(true);
        final String[] backups = ManagerFactory.getRecoveryManager(context).getBackupList();
        mSelectedBackup = backups.length > 0 ? 0 : -1;

        alert.setSingleChoiceItems(backups, mSelectedBackup, new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                mSelectedBackup = which;
            }
        });

        alert.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int whichButton) {
                dialog.dismiss();

                if (mSelectedBackup >= 0) {
                    final String toDelete = backupFolder + backups[mSelectedBackup];

                    final ProgressDialog pDialog = new ProgressDialog(context);
                    pDialog.setIndeterminate(true);
                    pDialog.setMessage(context.getResources().getString(
                            R.string.alert_deleting_folder,
                            new Object[] { backups[mSelectedBackup] }));
                    pDialog.setCancelable(false);
                    pDialog.setCanceledOnTouchOutside(false);
                    pDialog.show();

                    (new Thread() {

                        public void run() {

                            recursiveDelete(new File(toDelete));

                            pDialog.dismiss();
                        }
                    }).start();
                }
            }
        });

        alert.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        alert.show();

    }

    public void selectDownloadPath(final Activity activity) {
        final EditText input = new EditText(activity);
        input.setText(ManagerFactory.getPreferencesManager(activity).getDownloadPath());

        new AlertDialog.Builder(activity)
                .setTitle(R.string.download_alert_title)
                .setMessage(R.string.download_alert_summary)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface dialog, int whichButton) {
                        String value = input.getText().toString();

                        if (value == null || "".equals(value.trim()) || !value.startsWith("/")) {
                            Toast.makeText(activity, R.string.download_alert_error,
                                    Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            return;
                        }

                        ManagerFactory.getPreferencesManager(activity).setDownloadPath(value);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface dialog, int whichButton) {
                        dialog.dismiss();
                    }
                }).show();
    }

    public void download(Context context, String url, String fileName, String md5, boolean isDelta,
            int notificationId) {
        Intent intent = new Intent(context, DownloadService.class);
        DownloadService.FileInfo info = new DownloadService.FileInfo();
        info.notificationId = notificationId;
        info.url = url;
        info.fileName = fileName;
        info.md5 = md5;
        info.isDelta = isDelta;
        intent.putExtra(Constants.FILE_INFO, info);
        context.startService(intent);
    }

    public void cancelDownload(final Context context, final int notificationId,
            DownloadService.FileInfo fileInfo) {
        
        switch (notificationId) {
            case Constants.DOWNLOADROM_NOTIFICATION_ID:
            case Constants.DOWNLOADGAPPS_NOTIFICATION_ID:
            case Constants.DOWNLOADTWRP_NOTIFICATION_ID:
                if (fileInfo.status == Status.FINISHED) {
                    return;
                }
                break;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.download_cancel_title)
                .setMessage(R.string.download_cancel_message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface dialog, int whichButton) {
                        dialog.dismiss();
                        NotificationManager nMgr = (NotificationManager) context
                                .getSystemService(Context.NOTIFICATION_SERVICE);
                        nMgr.cancel(notificationId);
                        Intent intent = new Intent(context, DownloadService.class);
                        context.stopService(intent);
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {

                    public void onClick(DialogInterface dialog, int whichButton) {
                        dialog.dismiss();
                    }
                }).show();
    }

    public boolean recursiveDelete(File f) {
        try {
            if (f.isDirectory()) {
                File[] files = f.listFiles();
                for (int i = 0; i < files.length; i++) {
                    if (!recursiveDelete(files[i])) {
                        return false;
                    }
                }
                if (!f.delete()) {
                    return false;
                }
            } else {
                if (!f.delete()) {
                    return false;
                }
            }
        } catch (Exception ignore) {
        }
        return true;
    }

    public boolean writeToFile(String data, String path, String fileName) {

        File folder = new File(path);
        File file = new File(folder, fileName);

        folder.mkdirs();

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(data.getBytes());
            return true;
        } catch (IOException ex) {
            ex.printStackTrace();
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ex) {
                }
            }
        }
    }

    public String readAssets(Context contex, String fileName) {
        BufferedReader in = null;
        StringBuilder data = null;
        try {
            data = new StringBuilder(2048);
            char[] buf = new char[2048];
            int nRead = -1;
            in = new BufferedReader(new InputStreamReader(contex.getAssets().open(fileName)));
            while ((nRead = in.read(buf)) != -1) {
                data.append(buf, 0, nRead);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
        }

        if (TextUtils.isEmpty(data)) {
            return null;
        }
        return data.toString();
    }

    public boolean hasExternalStorage() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    public boolean isExternalStorage(String path) {
        return !path.startsWith(mInternalStoragePath) && !path.startsWith("/sdcard")
                && !path.startsWith("/mnt/sdcard");
    }

    private void readMounts() {

        ArrayList<String> mounts = new ArrayList<String>();
        ArrayList<String> vold = new ArrayList<String>();

        try {
            Scanner scanner = new Scanner(new File("/proc/mounts"));
            while (scanner.hasNext()) {
                String line = scanner.nextLine();
                if (line.startsWith("/dev/block/vold/")) {
                    String[] lineElements = line.split(" ");
                    String element = lineElements[1];

                    mounts.add(element);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (mounts.size() == 0 || (mounts.size() == 1 && hasExternalStorage())) {
            mounts.add("/mnt/sdcard");
        }

        try {
            Scanner scanner = new Scanner(new File("/system/etc/vold.fstab"));
            while (scanner.hasNext()) {
                String line = scanner.nextLine();
                if (line.startsWith("dev_mount")) {
                    String[] lineElements = line.split(" ");
                    String element = lineElements[2];

                    if (element.contains(":")) {
                        element = element.substring(0, element.indexOf(":"));
                    }

                    if (element.toLowerCase().indexOf("usb") < 0) {
                        vold.add(element);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (vold.size() == 0 || (vold.size() == 1 && hasExternalStorage())) {
            vold.add("/mnt/sdcard");
        }

        for (int i = 0; i < mounts.size(); i++) {
            String mount = mounts.get(i);
            File root = new File(mount);
            if (!vold.contains(mount)
                    || (!root.exists() || !root.isDirectory() || !root.canWrite())) {
                mounts.remove(i--);
            }
        }

        for (int i = 0; i < mounts.size(); i++) {
            String mount = mounts.get(i);
            if (mount.indexOf("sdcard0") >= 0 || mount.equalsIgnoreCase("/mnt/sdcard")
                    || mount.equalsIgnoreCase("/sdcard")) {
                mInternalStoragePath = mount;
            } else {
                mExternalStoragePath = mount;
            }
        }

        if (mInternalStoragePath == null) {
            mInternalStoragePath = "/sdcard";
        }
    }

    public void addFilesToZip(File inFile, File outFile, File[] files, String folder) throws IOException {
        byte[] buf = new byte[1024];

        ZipOutputStream out = null;
        try {
            out = new ZipOutputStream(new FileOutputStream(outFile));
            ZipInputStream in = null;
            try {
                in = new ZipInputStream(new FileInputStream(inFile));

                ZipEntry entry = in.getNextEntry();
                while (entry != null) {
                    String name = entry.getName();
                    boolean alreadyAdded = false;
                    for (File f : files) {
                        if (f.getName().equals(name)) {
                            alreadyAdded = true;
                            break;
                        }
                    }
                    if (!alreadyAdded) {

                        out.putNextEntry(new ZipEntry(name));

                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    entry = in.getNextEntry();
                }
            } finally {
                if (in != null) {
                    in.close();
                }
            }

            for (int i = 0; i < files.length; i++) {
                InputStream fin = null;
                try {
                    fin = new FileInputStream(files[i]);
                    String file = files[i].getAbsolutePath();
                    file = file.replace(folder, "");
                    out.putNextEntry(new ZipEntry(file));

                    int len;
                    while ((len = fin.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.closeEntry();
                } finally {
                    if (fin != null) {
                        fin.close();
                    }
                }
            }
        } finally {
            if (out != null) {
                out.close();
            }
            inFile.delete();
        }
    }

    public void read(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf, 0, buf.length)) != -1) {
                out.write(buf, 0, len);
            }

        } finally {
            in.close();
            out.close();
        }
    }
}