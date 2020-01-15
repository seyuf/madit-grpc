/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// The CloudSpeechSession streams audio to the server until the endpointer tells it to stop. It
// therefore must be repeatedly reopened for continuous transcription. The response observer gets
// data back from the server. Our CloudSpeechStreamObserver extracts the speech and the confidence
// and passes the data to a SpeechSessionListener, which helps to aggregate
// TranscriptionResults and manage the repeatedly reopening sessions.

package com.madit.audio.asr.cloud;

import android.util.Log;

import com.madit.audio.StreamingAudioEncoder;
import com.madit.audio.asr.CloudSpeechSessionParams;
import com.madit.audio.asr.SpeechRecognitionModelOptions;
import com.madit.audio.asr.SpeechSession;
import com.madit.audio.asr.SpeechSessionListener;
import com.madit.cloud.speech.v1p1beta1.RecognitionAudio;
import com.madit.cloud.speech.v1p1beta1.RecognitionConfig;
import com.madit.cloud.speech.v1p1beta1.SpeechContext;
import com.madit.cloud.speech.v1p1beta1.StreamingRecognitionConfig;
import com.madit.cloud.speech.v1p1beta1.StreamingRecognizeRequest;
import com.google.common.flogger.FluentLogger;
import com.google.protobuf.ByteString;
import com.madit.audio.service.CustomSpeechGrpc;

import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Random;

import org.joda.time.Duration;

/**
 * Lightweight wrapper around the GRPC Google Cloud Speech API. It can handle one streaming
 * recognition request at a time.
 */
public class CloudSpeechSession extends SpeechSession {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private CustomSpeechGrpc.SpeechStub speechClient;

  // Since the speech session times out after 5 minutes, we should try to avoid sessions reaching
  // approximately that length. If speech begins 4 mins and 30 seconds into the transcription, it
  // will likely be cut off. Therefore, we close sessions that haven't received any results in an
  // extended period of time.
  public static final Duration CLOSE_SESSION_AFTER_EXTENDED_SILENCE_DURATION =
      Duration.standardMinutes(4).plus(Duration.standardSeconds(30));

  /**
   * The stream observer and cloud interaction functions are marked protected so that they may be
   * replaced with a local server simulation in testing.
   */
  private CloudSpeechStreamObserver responseObserver;

  private StreamObserver<StreamingRecognizeRequest> requestObserver;

  private final CloudSpeechSessionParams params;
  private final SpeechSessionListener speechSessionListener;
  private int sampleRateHz;

  private boolean stillSendingRequests = false;
  private final ManagedChannel channel;

  private StreamingAudioEncoder encoder;
  private boolean encoderIsRequested;
  private boolean encoderIsSupported;
  private boolean isFirst = false;
  //madit

  /*
   * @param speechSessionListener Listener for recognition responses.
   * @param sampleRateHz Sample rate of microphone data.
   * @param channel The channel to use for cloud communication.
   */
  public CloudSpeechSession(
      CloudSpeechSessionParams params,
      SpeechSessionListener speechSessionListener,
      int sampleRateHz,
      ManagedChannel channel) {
    this.params = params;
    this.channel = channel;
    this.speechSessionListener = speechSessionListener;
    this.sampleRateHz = sampleRateHz;
    this.encoder = new StreamingAudioEncoder();
  }

  /** Starts a streaming speech recognition request. */
  @Override
  public synchronized void initImpl(
      SpeechRecognitionModelOptions modelOptions, int chunkSizeSamples) {
    if (chunkSizeSamples < 0.050 * sampleRateHz) {
      logger.atWarning().log(
          "Your buffer size is less than 50ms, you may have poor performance getting "
              + "streaming results.");
    }
    responseObserver = makeResponseObserver(speechSessionListener);
    encoderIsRequested = params.getEncoderParams().getEnableEncoder();
    encoderIsSupported =
        StreamingAudioEncoder.isEncoderSupported(params.getEncoderParams().getCodec());
    if (usingEncoder()) {
      try {
        encoder.init(
            sampleRateHz,
            params.getEncoderParams().getCodec(),
            params.getEncoderParams().getAllowVbr());
      } catch (StreamingAudioEncoder.EncoderException | IOException e) {
        e.printStackTrace();
        logger.atSevere().log("Encoder could not be created. Using uncompressed audio.");
        encoderIsRequested = false;
      }
    }
    initServer(modelOptions);

    stillSendingRequests = true;
  }

  /** Returns true when the encoder is being used. */
  public boolean usingEncoder() {
    return encoderIsRequested && encoderIsSupported;
  }

  private CloudSpeechStreamObserver makeResponseObserver(
      SpeechSessionListener speechSessionListener) {
    return new CloudSpeechStreamObserver(
        params.getObserverParams(), speechSessionListener, sessionID());
  }

  /**
   * Sends an audio buffer to the Cloud Speech Server.
   *
   * @param buffer 16 bit LinearPCM byte array.
   * @param offset first element of buffer to use.
   * @param count number of elements of buffer to use.
   * @return true if audio data was processed, false if session was already requested to close. You
   *     should wait for the recognition listener passed into the constructor to receive
   *     OK_TO_TERMINATE before destroying the session.
   */
  @Override
  public synchronized boolean processAudioBytesImpl(byte[] buffer, int offset, int count) {
    if (!isStillSendingRequests()) {
      return false;
    }

    if (usingEncoder()) {
      byte[] encoded = encoder.processAudioBytes(buffer, offset, count);
      if (encoded.length > 0) {
        streamToServer(encoded, 0, encoded.length);
      }
    } else {
      streamToServer(buffer, offset, count);
    }

    if (CLOSE_SESSION_AFTER_EXTENDED_SILENCE_DURATION.isShorterThan(
        responseObserver.timeSinceLastServerActivity())) {
      logger.atInfo().log(
          "Session #%d scheduled to be ended due to extended silence.", sessionID());
      requestCloseSession();
    }
    return true;
  }

  private boolean isStillSendingRequests() {
    return stillSendingRequests && responseObserver.isStillListening();
  }

  /**
   * Closes the current recognition request on the client end. This does not immediately end the
   * session. Only once the server acknowledges the closing of the session is communication
   * complete.
   */
  @Override
  public synchronized void requestCloseSessionImpl() {
    if (stillSendingRequests) {
      stillSendingRequests = false;
      if (usingEncoder()) {
        // Get any remaining output from the codec and stop.
        byte[] data = encoder.flushAndStop();
        streamToServer(data, 0, data.length);
      }
      closeServer();
    }
  }

  @Override
  public boolean requiresNetworkConnection() {
    return true;
  }

  private void initServer(SpeechRecognitionModelOptions modelOptions) {
    this.speechClient = CustomSpeechGrpc.newStub(channel);
    requestObserver = speechClient.streamingRecognize(responseObserver);

    // Build and send a StreamingRecognizeRequest containing the parameters for
    // processing the audio.
    SpeechContext speechContext = SpeechContext.getDefaultInstance();

    //com.madit.cloud.speech.v1p1beta1.RecognitionConfig.newBuilder().setRaw()
    RecognitionConfig.AudioEncoding encodingType = RecognitionConfig.AudioEncoding.LINEAR16;
    if (usingEncoder()) {
      switch (encoder.getCodecType()) {
        case AMRWB:
          encodingType = RecognitionConfig.AudioEncoding.AMR_WB;
          break;
        case FLAC:
          encodingType = RecognitionConfig.AudioEncoding.FLAC;
          break;
        case OGG_OPUS:
          encodingType = RecognitionConfig.AudioEncoding.OGG_OPUS;
          break;
        default:
      }
    }
    RecognitionConfig.Builder configBuilder =
        RecognitionConfig.newBuilder()
            .setEncoding(encodingType)
            .setSampleRateHertz(sampleRateHz)
            .setAudioChannelCount(1)
            .addSpeechContexts(speechContext)
            .setLanguageCode(modelOptions.getLocale())
            .addSpeechContexts(
                SpeechContext.newBuilder()
                    .addAllPhrases(modelOptions.getBiasWordsList()));

    StreamingRecognitionConfig.Builder strbuilder =
        StreamingRecognitionConfig.newBuilder()
            .setInterimResults(true)
            .setSingleUtterance(false);
    switch (modelOptions.getModel()) {
      case VIDEO:
        if (!modelOptions.getLocale().equals("en-US")) {
          logger.atSevere().log("Only en-US is supported by YouTube Livestream model");
        }
        configBuilder.setModel("video");
        break;
      case DICTATION_DEFAULT:
        configBuilder.setModel("default");
        break;
    }


    //madit
    configBuilder.setModel("default");
    configBuilder.setLanguageCode("fr-FR");



    byte[] randomWav = bang();

    int size = randomWav.length;
    short[] shortArray = new short[size+1];

    for (int index = 0; index < size/2; index++)
      shortArray[index] = (short) randomWav[index];

    Wave testWav = new Wave(16000, (short) 1, shortArray,0, 0);
    
    RecognitionAudio recognitionAudio =
            RecognitionAudio.newBuilder()
                    .setContent(ByteString.copyFrom(testWav.output)).build();

    RecognitionConfig config = configBuilder
            .setDataBytes(size)
            .setMaxAlternatives(2)
            .setRaw(true)
            .build();

    //StreamingRecognitionConfig streamingConfig = strbuilder.setConfig(config).build();
    // First request sends the configuration.
    StreamingRecognizeRequest initial =
        StreamingRecognizeRequest.newBuilder()
                .setAudio(recognitionAudio)
                .setConfig(config)
                .setUuid( String.valueOf(sessionID()))
                .build();

    Log.d("MADINITSERVER", "Content bytes: "+ initial.getAudio().getContent());
    requestObserver.onNext(initial);
  }


  public static byte[] bang() {
    byte[] buf = new byte[8050];
    Random r = new Random();
    boolean silence = true;
    for (int i = 0; i < 8000; i++) {
      while (r.nextInt() % 10 != 0) {
        buf[i] =
                silence ? 0
                        : (byte) Math.abs(r.nextInt()
                        % (int) (1. + 63. * (1. + Math.cos(((double) i)
                        * Math.PI / 8000.))));
        i++;
      }
      silence = !silence;
    }
    return buf;
  }




  private void streamToServer(byte[] buffer, int offset, int count) {

   // if(isFirst) return;
    RecognitionAudio recognitionAudio =
            RecognitionAudio.newBuilder()
                    .setContent(ByteString.copyFrom(buffer, offset, count)).build();

    RecognitionConfig config = RecognitionConfig.newBuilder()
            .setModel("default")
            .setLanguageCode("fr-FR")
            .setDataBytes(count)
            .setMaxAlternatives(2)
            .setRaw(true)
            .build();

    StreamingRecognizeRequest request =
        StreamingRecognizeRequest.newBuilder()
            //.setAudioContent(ByteString.copyFrom(buffer, offset, count))
                .setAudio(recognitionAudio)
                .setConfig(config)
                .setUuid( String.valueOf(sessionID()))
            .build();
    Log.d("MADTOSERVER", "Content bytes: "+request.getAudio().getContent());
    requestObserver.onNext(request);
    isFirst = true;
  }

  private void closeServer() {
    if (requestObserver != null) {
      // Tell the server we're done sending.
      requestObserver.onCompleted();
      requestObserver = null;
    }
  }



  private class Wave
  {
    private final int LONGINT = 4;
    private final int SMALLINT = 2;
    private final int INTEGER = 4;
    private final int ID_STRING_SIZE = 4;
    private final int WAV_RIFF_SIZE = LONGINT+ID_STRING_SIZE;
    private final int WAV_FMT_SIZE = (4*SMALLINT)+(INTEGER*2)+LONGINT+ID_STRING_SIZE;
    private final int WAV_DATA_SIZE = ID_STRING_SIZE+LONGINT;
    private final int WAV_HDR_SIZE = WAV_RIFF_SIZE+ID_STRING_SIZE+WAV_FMT_SIZE+WAV_DATA_SIZE;
    private final short PCM = 1;
    private final int SAMPLE_SIZE = 2;
    int cursor, nSamples;
    public byte[] output;

    public Wave(int sampleRate, short nChannels, short[] data, int start, int end)
    {
      nSamples=end-start+1;
      cursor=0;
      output=new byte[nSamples*SMALLINT+WAV_HDR_SIZE];
      buildHeader(sampleRate,nChannels);
      writeData(data,start,end);
    }
    // ------------------------------------------------------------
    private void buildHeader(int sampleRate, short nChannels)
    {
      write("RIFF");
      write(output.length);
      write("WAVE");
      writeFormat(sampleRate, nChannels);
    }
    // ------------------------------------------------------------
    public void writeFormat(int sampleRate, short nChannels)
    {
      write("fmt ");
      write(WAV_FMT_SIZE-WAV_DATA_SIZE);
      write(PCM);
      write(nChannels);
      write(sampleRate);
      write(nChannels * sampleRate * SAMPLE_SIZE);
      write((short)(nChannels * SAMPLE_SIZE));
      write((short)16);
    }
    // ------------------------------------------------------------
    public void writeData(short[] data, int start, int end)
    {
      write("data");
      write(nSamples*SMALLINT);
      for(int i=start; i<=end; write(data[i++]));
    }
    // ------------------------------------------------------------
    private void write(byte b)
    {
      output[cursor++]=b;
    }
    // ------------------------------------------------------------
    private void write(String id)
    {
      if(id.length()!=ID_STRING_SIZE) Log.d("MADITWAV","String "+id+" must have four characters.");
      else {
        for(int i=0; i<ID_STRING_SIZE; ++i) write((byte)id.charAt(i));
      }
    }
    // ------------------------------------------------------------
    private void write(int i)
    {
      write((byte) (i&0xFF)); i>>=8;
      write((byte) (i&0xFF)); i>>=8;
      write((byte) (i&0xFF)); i>>=8;
      write((byte) (i&0xFF));
    }
    // ------------------------------------------------------------
    private void write(short i)
    {
      write((byte) (i&0xFF)); i>>=8;
      write((byte) (i&0xFF));
    }
    // ------------------------------------------------------------

  }

}
