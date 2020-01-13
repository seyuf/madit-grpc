package com.madit.audio.service;


import android.content.SharedPreferences;
import android.support.v4.util.Pair;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.flogger.FluentLogger;

import net.gjerull.etherpad.client.EPLiteClient;

import org.joda.time.DateTime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * A utility for performing read/write operations on Drive files via the REST API and opening a
 * file picker UI via Storage Access Framework.
 */
public class EtherPadServiceHelper {
    private final Executor mExecutor = Executors.newSingleThreadExecutor();
    private final EPLiteClient client;
    private final SharedPreferences sharedPreferences;
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    public EtherPadServiceHelper(String apiEndpoint, String apiKey, SharedPreferences preferences) {
        this.client = new EPLiteClient(apiEndpoint, apiKey);
        this.sharedPreferences = preferences;
        isApiLimitRateAttained();
    }


    public boolean isApiLimitRateAttained(){
        //set limitrate 30 min/month
        int savedMonth = sharedPreferences.getInt("savedMonth", 0);
        int curMonth = DateTime.now().getMonthOfYear();
        if(savedMonth != curMonth){
            sharedPreferences.edit().apply();
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt("savedMonth", curMonth);
            editor.putLong("usedSeconds", 0);
            editor.apply();
            return false;
        }
        else {
            long usedSeconds = sharedPreferences.getLong("usedSeconds",0);
            /*logger.atInfo().log(
                    "Mois %d et secondes d'utilisation: %s",curMonth,usedSeconds); */
            return usedSeconds >= 1800;
        }
    }
    /**
     * Creates a text file in the user's My Drive folder and returns its file ID.
     */
    public Task<String> addOrGetGroupPadID(String padName, String groupId) {
        return Tasks.call(mExecutor, () -> {
            String padID = sharedPreferences.getString("padID", "");
            if (padID.isEmpty())
                padID = (String) client.createGroupPad(groupId, padName).getOrDefault("padID", "");
            else return padID;

            if(!padID.isEmpty()){
                client.setPublicStatus(padID, true);
                sharedPreferences.edit().apply();
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("padID", padID);
                editor.apply();
                return sharedPreferences.getString("padID", "");
            }
            else throw new IOException("Null result when requesting group pad creation.");
        });


    }


    /**
     * Creates a text file in the user's My Drive folder and returns its file ID.
     */
    public Task<String> addOrGetGroupIDTask() {
        return Tasks.call(mExecutor, () -> {

            String groupID = sharedPreferences.getString("groupID", "");
            if( groupID.isEmpty() )
                groupID =  (String) client.createGroup().getOrDefault("groupID", "");
            else return groupID;

            if (!groupID.isEmpty()) {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("groupID", groupID);
                editor.apply();
                return sharedPreferences.getString("groupID", "");
            } else throw new IOException("Null result when requesting group creation.");

        });

    }

    /**
     * Updates the file identified by {@code fileId} with the given {@code name} and {@code
     * content}.
     */
    public Task<Void> saveToDocument(String content, String padID) {
        return Tasks.call(mExecutor, () -> {

            client.appendText(padID, content);
            return null;

        });
    }

}
