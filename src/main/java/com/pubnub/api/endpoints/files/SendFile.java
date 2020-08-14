package com.pubnub.api.endpoints.files;

import com.pubnub.api.PubNub;
import com.pubnub.api.PubNubException;
import com.pubnub.api.builder.PubNubErrorBuilder;
import com.pubnub.api.callbacks.PNCallback;
import com.pubnub.api.endpoints.files.requiredparambuilder.BuilderSteps.ChannelStep;
import com.pubnub.api.endpoints.files.requiredparambuilder.BuilderSteps.FileIdStep;
import com.pubnub.api.endpoints.files.requiredparambuilder.BuilderSteps.FileNameStep;
import com.pubnub.api.endpoints.files.requiredparambuilder.BuilderSteps.InputStreamStep;
import com.pubnub.api.endpoints.remoteaction.ComposableRemoteAction;
import com.pubnub.api.endpoints.remoteaction.MappingRemoteAction;
import com.pubnub.api.endpoints.remoteaction.RemoteAction;
import com.pubnub.api.managers.RetrofitManager;
import com.pubnub.api.managers.TelemetryManager;
import com.pubnub.api.models.consumer.PNErrorData;
import com.pubnub.api.models.consumer.PNStatus;
import com.pubnub.api.models.consumer.files.PNBaseFile;
import com.pubnub.api.models.consumer.files.PNFileUploadResult;
import com.pubnub.api.models.consumer.files.PNPublishFileMessageResult;
import com.pubnub.api.models.server.files.FileUploadRequestDetails;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

@Accessors(chain = true, fluent = true)
public class SendFile implements RemoteAction<PNFileUploadResult> {

    private final RemoteAction<PNFileUploadResult> sendFileMultistepAction;
    private final String channel;
    private final String fileName;
    private final InputStream inputStream;
    private final ExecutorService executorService;
    @Setter
    private Object message;
    @Setter
    private Object meta;
    @Setter
    private Integer ttl;
    @Setter
    private Boolean shouldStore;
    @Setter
    private String cipherKey;

    SendFile(String channel,
             String fileName,
             InputStream inputStream,
             GenerateUploadUrl.Factory generateUploadUrlFactory,
             ChannelStep<FileNameStep<FileIdStep<PublishFileMessage>>> publishFileMessageBuilder,
             UploadFile.Factory sendFileToS3Factory,
             ExecutorService executorService) {
        this.channel = channel;
        this.fileName = fileName;
        this.inputStream = inputStream;
        this.executorService = executorService;
        this.sendFileMultistepAction = sendFileComposedActions(
                generateUploadUrlFactory,
                publishFileMessageBuilder,
                sendFileToS3Factory);
    }

    public PNFileUploadResult sync() throws PubNubException {
        validate();
        return sendFileMultistepAction.sync();
    }

    public void async(@NotNull PNCallback<PNFileUploadResult> callback) {
        executorService
                .execute(() -> {
                    try {
                        validate();
                        sendFileMultistepAction.async(callback);
                    } catch (PubNubException ex) {
                        callback.onResponse(null,
                                PNStatus.builder()
                                        .error(true)
                                        .errorData(new PNErrorData(ex.getErrormsg(), ex))
                                        .build());
                    }
                });
    }

    private void validate() throws PubNubException {
        if (channel == null || channel.isEmpty()) {
            throw PubNubException.builder().pubnubError(PubNubErrorBuilder.PNERROBJ_CHANNEL_MISSING).build();
        }

        if (inputStream == null) {
            throw PubNubException.builder().pubnubError(PubNubErrorBuilder.PNERROBJ_INVALID_ARGUMENTS)
                    .errormsg("Input stream cannot be null").build();
        }

        if (fileName == null || fileName.isEmpty()) {
            throw PubNubException.builder().pubnubError(PubNubErrorBuilder.PNERROBJ_INVALID_ARGUMENTS)
                    .errormsg("File name cannot be null nor empty").build();
        }
    }

    private RemoteAction<PNFileUploadResult> sendFileComposedActions(
            GenerateUploadUrl.Factory generateUploadUrlFactory,
            ChannelStep<FileNameStep<FileIdStep<PublishFileMessage>>> publishFileMessageBuilder,
            UploadFile.Factory sendFileToS3Factory) {
        final AtomicReference<FileUploadRequestDetails> result = new AtomicReference<>();
        return ComposableRemoteAction
                .firstDo(generateUploadUrlFactory.create(channel, fileName))
                .then(res -> {
                    result.set(res);
                    return sendToS3(res, sendFileToS3Factory);
                })
                .checkpoint()
                .then(res -> publishFileMessageBuilder.channel(channel)
                        .fileName(result.get().getData().getName())
                        .fileId(result.get().getData().getId())
                        .message(message)
                        .meta(meta)
                        .ttl(ttl)
                        .shouldStore(shouldStore))
                .then(res -> mapPublishFileMessageToFileUpload(result, res));
    }

    @NotNull
    private RemoteAction<PNFileUploadResult> mapPublishFileMessageToFileUpload(AtomicReference<FileUploadRequestDetails> result,
                                                                               PNPublishFileMessageResult res) {
        return MappingRemoteAction.map(res,
                pnPublishFileMessageResult -> new PNFileUploadResult(pnPublishFileMessageResult.getTimetoken(),
                        HttpURLConnection.HTTP_OK,
                        new PNBaseFile(result.get().getData().getId(), result.get().getData().getName())));
    }

    @Override
    public void retry() {
        sendFileMultistepAction.retry();
    }

    @Override
    public void silentCancel() {
        sendFileMultistepAction.silentCancel();
    }

    private RemoteAction<Void> sendToS3(FileUploadRequestDetails result,
                                        UploadFile.Factory sendFileToS3Factory) {
        return sendFileToS3Factory.create(fileName, inputStream, cipherKey, result);
    }

    public static Builder builder(PubNub pubnub,
                                  TelemetryManager telemetry,
                                  RetrofitManager retrofit) {
        return new Builder(pubnub, telemetry, retrofit);
    }

    public static class Builder implements ChannelStep<FileNameStep<InputStreamStep<SendFile>>> {

        private final PubNub pubnub;
        private final TelemetryManager telemetry;
        private final RetrofitManager retrofit;

        Builder(PubNub pubnub,
                TelemetryManager telemetry,
                RetrofitManager retrofit) {

            this.pubnub = pubnub;
            this.telemetry = telemetry;
            this.retrofit = retrofit;
        }

        @Override
        public FileNameStep<InputStreamStep<SendFile>> channel(String channel) {
            return new InnerBuilder(pubnub, telemetry, retrofit).channel(channel);
        }

        public static class InnerBuilder implements
                ChannelStep<FileNameStep<InputStreamStep<SendFile>>>,
                FileNameStep<InputStreamStep<SendFile>>,
                InputStreamStep<SendFile> {
            private final RetrofitManager retrofit;
            private String channelValue;
            private String fileNameValue;
            private final PublishFileMessage.Builder publishFileMessageBuilder;
            private final UploadFile.Factory uploadFileFactory;
            private final GenerateUploadUrl.Factory generateUploadUrlFactory;

            private InnerBuilder(PubNub pubnub,
                                 TelemetryManager telemetry,
                                 RetrofitManager retrofit) {
                this.retrofit = retrofit;
                this.publishFileMessageBuilder = PublishFileMessage.builder(pubnub, telemetry, retrofit);
                this.uploadFileFactory = new UploadFile.Factory(pubnub, retrofit);
                this.generateUploadUrlFactory = new GenerateUploadUrl.Factory(pubnub, telemetry, retrofit);
            }

            @Override
            public FileNameStep<InputStreamStep<SendFile>> channel(String channel) {
                this.channelValue = channel;
                return this;
            }

            @Override
            public InputStreamStep<SendFile> fileName(String fileName) {
                this.fileNameValue = fileName;
                return this;
            }

            @Override
            public SendFile inputStream(InputStream inputStream) {
                return new SendFile(channelValue,
                        fileNameValue,
                        inputStream,
                        generateUploadUrlFactory,
                        publishFileMessageBuilder,
                        uploadFileFactory,
                        retrofit.getTransactionClientExecutorService());
            }
        }
    }

}
