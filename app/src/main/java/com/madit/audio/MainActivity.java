
package com.madit.audio;

import static com.madit.audio.asr.SpeechRecognitionModelOptions.SpecificModel.DICTATION_DEFAULT;
import static com.madit.audio.asr.SpeechRecognitionModelOptions.SpecificModel.VIDEO;
import static com.madit.audio.asr.TranscriptionResultFormatterOptions.TranscriptColoringStyle.NO_COLORING;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.Html;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.madit.audio.CodecAndBitrate;
import com.madit.audio.asr.CloudSpeechSessionParams;
import com.madit.audio.asr.CloudSpeechStreamObserverParams;
import com.madit.audio.asr.RepeatingRecognitionSession;
import com.madit.audio.asr.SafeTranscriptionResultFormatter;
import com.madit.audio.asr.SpeechRecognitionModelOptions;
import com.madit.audio.asr.TranscriptionResultFormatterOptions;
import com.madit.audio.asr.TranscriptionResultUpdatePublisher;
import com.madit.audio.asr.TranscriptionResultUpdatePublisher.ResultSource;
import com.madit.audio.asr.cloud.CloudSpeechSessionFactory;
import com.madit.audio.service.EtherPadServiceHelper;

import java.util.UUID;

public class MainActivity extends AppCompatActivity {

  /*****************DRIVE***************************/
  private static final String TAG = "MainActivity";


  private SharedPreferences sharedpreferences;

  private EtherPadServiceHelper etherPadServiceHelper;
  private String openPadId;


  /*****************END DRIVE***************************/

  private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

  private static final int MIC_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
  private static final int MIC_CHANNEL_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
  private static final int MIC_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
  private static final int SAMPLE_RATE = 16000;
  private static final int CHUNK_SIZE_SAMPLES = 1280;
  private static final int BYTES_PER_SAMPLE = 2;
  private static final String SHARE_PREF_API_KEY = "AIzaSyAUSLQgpvmWKIxb7AOGS5MjyjkRzsZH3Vs";

  private int currentLanguageCodePosition;
  private String currentLanguageCode;

  private AudioRecord audioRecord;
  private final byte[] buffer = new byte[BYTES_PER_SAMPLE * CHUNK_SIZE_SAMPLES];

  // This class was intended to be used from a thread where timing is not critical (i.e. do not
  // call this in a system audio callback). Network calls will be made during all of the functions
  // that RepeatingRecognitionSession inherits from SampleProcessorInterface.
  private RepeatingRecognitionSession recognizer;
  private NetworkConnectionChecker networkChecker;
  private TextView transcript;

  private void writeToPad(String openPadId, String text) {

    if (etherPadServiceHelper != null && openPadId != null) {
      Log.d(TAG,"updating document with ID: ".concat(openPadId));
      etherPadServiceHelper.saveToDocument (text, openPadId)
              .addOnFailureListener(exception ->
                      Log.e(TAG, "Unable save new content doc via REST.", exception));
    }
    else Log.e(TAG, "Unable to save new content:", new Exception("No connection"));

  }

  private void initEtherPad(){
    etherPadServiceHelper = new EtherPadServiceHelper(
            "https://pad.madit.fr/",
            "712b2a175d2f74ff60c4ad7ad1f0d17a7020bb4c59bb52dce9e635d419ce288b",
            this.sharedpreferences
            );
    setMainPadID();

  }
  private void setMainPadID(){

    if (etherPadServiceHelper != null) {
      Log.d(TAG, "Querying for files.");

      etherPadServiceHelper.addOrGetGroupIDTask().addOnSuccessListener(groupId ->{
        String padName = getUniquePsuedoID();
        etherPadServiceHelper.addOrGetGroupPadID(padName, groupId).addOnSuccessListener(padId -> {
         openPadId = padId;
          Log.d(TAG, "Document ID is: ".concat(openPadId));
        }).addOnFailureListener(exception ->
                        Log.e(TAG, "Couldn't create document.", exception));
      }).addOnFailureListener(exception ->
                      Log.e(TAG, "Couldn't create group.", exception));
    }
  }

  private final TranscriptionResultUpdatePublisher transcriptUpdater =
          (formattedTranscript, updateType) -> {
            runOnUiThread(
                    () -> {

                      transcript.setText(formattedTranscript.toString());
                      if(openPadId != null
                              && (updateType == TranscriptionResultUpdatePublisher
                              .UpdateType.TRANSCRIPT_FINALIZED)){

                        writeToPad(
                                openPadId,
                                recognizer.getLatestTextToSave().toString()
                        );
                      }
                    });
          };

  private Runnable readMicData =
          () -> {
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
              return;
            }
            recognizer.init(CHUNK_SIZE_SAMPLES);
            while (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
              audioRecord.read(buffer, 0, CHUNK_SIZE_SAMPLES * BYTES_PER_SAMPLE);
              recognizer.processAudioBytes(buffer);
            }
            recognizer.stop();
          };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    sharedpreferences = getPreferences(Context.MODE_PRIVATE);
    transcript = findViewById(R.id.transcript);
    initLanguageLocale();
  }

  @Override
  public void onStart() {
    super.onStart();

    //requestSignIn();
    initEtherPad();
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
              != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(
                this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
      } else {
        showAPIKeyDialog();
      }
  }

  @Override
  public void onStop() {
    super.onStop();
    if (audioRecord != null) {
      audioRecord.stop();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (recognizer != null) {
      recognizer.unregisterCallback(transcriptUpdater);
      networkChecker.unregisterNetworkCallback();
    }
  }

  @Override
  public void onRequestPermissionsResult(
          int requestCode, String[] permissions, int[] grantResults) {
    switch (requestCode) {
      case PERMISSIONS_REQUEST_RECORD_AUDIO:
        // If request is cancelled, the result arrays are empty.
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
          showAPIKeyDialog();
        } else {
          // This should nag user again if they launch without the permissions.
          Toast.makeText(
                  this,
                  "This app does not work without the Microphone permission.",
                  Toast.LENGTH_SHORT)
                  .show();
          finish();
        }
        return;
      default: // Should not happen. Something we did not request.
    }
  }

  private void initLanguageLocale() {
    // The default locale is en-US.
    currentLanguageCode = "fr-FR";
    currentLanguageCodePosition = 25;
  }

  private void constructRepeatingRecognitionSession() {
    SpeechRecognitionModelOptions options =
            SpeechRecognitionModelOptions.newBuilder()
                    .setLocale(currentLanguageCode)
                    // As of 7/18/19, Cloud Speech's video model supports en-US only.
                    .setModel(currentLanguageCode.equals("en-US") ? VIDEO : DICTATION_DEFAULT)
                    .build();
    CloudSpeechSessionParams cloudParams =
            CloudSpeechSessionParams.newBuilder()
                    .setObserverParams(
                            CloudSpeechStreamObserverParams.newBuilder().setRejectUnstableHypotheses(false))
                    .setFilterProfanity(true)
                    .setEncoderParams(
                            CloudSpeechSessionParams.EncoderParams.newBuilder()
                                    .setEnableEncoder(true)
                                    .setAllowVbr(true)
                                    .setCodec(CodecAndBitrate.OGG_OPUS_BITRATE_32KBPS))
                    .build();
    networkChecker = new NetworkConnectionChecker(this);
    networkChecker.registerNetworkCallback();

    // There are lots of options for formatting the text. These can be useful for debugging
    // and visualization, but it increases the effort of reading the transcripts.
    TranscriptionResultFormatterOptions formatterOptions =
            TranscriptionResultFormatterOptions.newBuilder()
                    .setTranscriptColoringStyle(NO_COLORING)
                    .build();
    RepeatingRecognitionSession.Builder recognizerBuilder =
            RepeatingRecognitionSession.newBuilder()
                    .setSpeechSessionFactory(new CloudSpeechSessionFactory(cloudParams, getApiKey(this)))
                    .setSampleRateHz(SAMPLE_RATE)
                    .setTranscriptionResultFormatter(new SafeTranscriptionResultFormatter(formatterOptions))
                    .setSpeechRecognitionModelOptions(options)
                    .setNetworkConnectionChecker(networkChecker);
    recognizer = recognizerBuilder.build();
    recognizer.registerCallback(transcriptUpdater, ResultSource.WHOLE_RESULT);
  }

  private void startRecording() {
    if (audioRecord == null) {
      audioRecord =
              new AudioRecord(
                      MIC_SOURCE,
                      SAMPLE_RATE,
                      MIC_CHANNELS,
                      MIC_CHANNEL_ENCODING,
                      CHUNK_SIZE_SAMPLES * BYTES_PER_SAMPLE);
    }

    audioRecord.startRecording();
    new Thread(readMicData).start();
  }

  /** The API won't work without a valid API key. This prompts the user to enter one. */
  private void showAPIKeyDialog() {
    LinearLayout contentLayout =
            (LinearLayout) getLayoutInflater().inflate(R.layout.api_key_message, null);

    TextView linkView = contentLayout.findViewById(R.id.api_key_link_view);
    linkView.setText(Html.fromHtml(getString(R.string.api_key_doc_link)));
    linkView.setMovementMethod(LinkMovementMethod.getInstance());
    EditText keyInput = contentLayout.findViewById(R.id.api_key_input);
    keyInput.setInputType(InputType.TYPE_CLASS_TEXT);
    keyInput.setText("FREE VERSION");

    TextView selectLanguageView = contentLayout.findViewById(R.id.language_locale_view);
    selectLanguageView.setText(Html.fromHtml(getString(R.string.select_language_message)));
    selectLanguageView.setMovementMethod(LinkMovementMethod.getInstance());
    final ArrayAdapter<String> languagesList =
            new ArrayAdapter<String>(
                    this,
                    android.R.layout.simple_spinner_item,
                    getResources().getStringArray(R.array.languages));
    Spinner sp = contentLayout.findViewById(R.id.language_locale_spinner);
    sp.setAdapter(languagesList);
    sp.setOnItemSelectedListener(
            new OnItemSelectedListener() {
              @Override
              public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                handleLanguageChanged(position);
              }

              @Override
              public void onNothingSelected(AdapterView<?> parent) {}
            });
    sp.setSelection(currentLanguageCodePosition);

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder
            .setTitle(getString(R.string.api_key_message))
            .setView(contentLayout)
            .setPositiveButton(
                    getString(android.R.string.ok),
                    (dialog, which) -> {
                      saveApiKey(this);
                      constructRepeatingRecognitionSession();
                      startRecording();
                    })
            .show();
  }

  /** Handles selecting language by spinner. */
  private void handleLanguageChanged(int itemPosition) {
    currentLanguageCodePosition = itemPosition;
    currentLanguageCode = getResources().getStringArray(R.array.language_locales)[itemPosition];
  }

  /** Saves the API Key in user shared preference. */
  private static void saveApiKey(Context context) {
    PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(SHARE_PREF_API_KEY, "AIzaSyAUSLQgpvmWKIxb7AOGS5MjyjkRzsZH3Vs")
            .commit();
  }

  /** Gets the API key from shared preference. */
  private static String getApiKey(Context context) {
    return PreferenceManager
            .getDefaultSharedPreferences(context)
            .getString(SHARE_PREF_API_KEY, "AIzaSyAUSLQgpvmWKIxb7AOGS5MjyjkRzsZH3Vs");
  }




  public static String getUniquePsuedoID() {
    // If all else fails, if the user does have lower than API 9 (lower
    // than Gingerbread), has reset their device or 'Secure.ANDROID_ID'
    // returns 'null', then simply the ID returned will be solely based
    // off their Android device information. This is where the collisions
    // can happen.
    // Thanks http://www.pocketmagic.net/?p=1662!
    // Try not to use DISPLAY, HOST or ID - these items could change.
    // If there are collisions, there will be overlapping data
    String m_szDevIDShort = "35" + (Build.BOARD.length() % 10) + (Build.BRAND.length() % 10) + (Build.CPU_ABI.length() % 10) + (Build.DEVICE.length() % 10) + (Build.MANUFACTURER.length() % 10) + (Build.MODEL.length() % 10) + (Build.PRODUCT.length() % 10);

    // Thanks to @Roman SL!
    // https://stackoverflow.com/a/4789483/950427
    // Only devices with API >= 9 have android.os.Build.SERIAL
    // http://developer.android.com/reference/android/os/Build.html#SERIAL
    // If a user upgrades software or roots their device, there will be a duplicate entry
    String serial = null;
    try {
      serial = android.os.Build.class.getField("SERIAL").get(null).toString();

      // Go ahead and return the serial for api => 9
      return new UUID(m_szDevIDShort.hashCode(), serial.hashCode()).toString();
    } catch (Exception exception) {
      // String needs to be initialized
      serial = "serial"; // some value
    }

    // Thanks @Joe!
    // https://stackoverflow.com/a/2853253/950427
    // Finally, combine the values we have found by using the UUID class to create a unique identifier
    return new UUID(m_szDevIDShort.hashCode(), serial.hashCode()).toString();
  }

}
