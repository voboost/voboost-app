package ru.voboost.ota;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import org.json.JSONObject;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function0;
import ru.voboost.AppDirectoryAuthority;
import ru.voboost.AppFileAuthorityKt;
import ru.voboost.AppFileIdentity;
import ru.voboost.AppReadAuthority;

/** Real Android transport gate. This class exists only in the instrumentation APK. */
public final class EmulatorInteropInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? Bundle.EMPTY : arguments;
        start();
    }

    @Override
    public void onStart() {
        String value = null;
        Throwable error = null;
        try {
            value = runInterop();
        } catch (Throwable failure) {
            error = failure;
        }
        JSONObject report = new JSONObject();
        try {
            report.put("schemaVersion", 1);
            report.put("scope", "emulator-transport-only-not-production-install");
            report.put("success", error == null);
            report.put("result", value);
            report.put("error", error == null ? null : Log.getStackTraceString(error));
        } catch (Exception jsonFailure) {
            throw new IllegalStateException(jsonFailure);
        }
        Bundle output = new Bundle();
        output.putString("stream", report + "\n");
        finish(error == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
    }

    private String runInterop() throws Exception {
        if ("unavailable".equals(arguments.getString("mode"))) {
            long started = android.os.SystemClock.elapsedRealtime();
            DaemonActiveResult result =
                    await(
                            DaemonActiveResult.class,
                            continuation ->
                                    new LocalSocketDaemonOtaGateway().activeRelease(continuation));
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;
            require(
                    result == DaemonActiveResult.Unavailable.INSTANCE,
                    "unavailable mode returned " + result);
            require(elapsed <= 5_000, "unavailable mode exceeded five seconds: " + elapsed);
            return "bounded-unavailable-ok-" + elapsed + "ms";
        }
        String rawActive = rawActiveRelease();
        require(
                rawActive.startsWith(
                        "{\"response\":\"activeRelease\",\"result\":{\"status\":\"active\""),
                "raw activeRelease response differs: " + rawActive);
        LocalSocketDaemonOtaGateway gateway = new LocalSocketDaemonOtaGateway();
        DaemonActiveResult first =
                await(
                        DaemonActiveResult.class,
                        continuation -> gateway.activeRelease(continuation));
        require(first instanceof DaemonActiveResult.Active, "first activeRelease was " + first);
        ActiveReleaseIdentity identity = ((DaemonActiveResult.Active) first).getIdentity();
        require("beta".equals(identity.getChannel()), "active channel differs");
        require(
                "1.0.0-beta1".equals(identity.getReleaseIdentity().getSource()),
                "active release differs");
        require(identity.getArtifacts().size() == 67, "active artifact count differs");

        File candidateDirectory = new File(getTargetContext().getFilesDir(), "interop");
        byte[] manifestBytes =
                Files.readAllBytes(new File(candidateDirectory, "voboost.json").toPath());
        OtaCandidateHandle candidate =
                new OtaCandidateHandle(
                        repeat("dd", 32),
                        "1.0.0-beta2",
                        sha256(manifestBytes),
                        Collections.emptyList());

        OtaCandidateFiles firstFiles = candidateFiles(candidateDirectory);
        DaemonProposalResult firstProposal;
        try {
            firstProposal =
                    await(
                            DaemonProposalResult.class,
                            continuation -> gateway.propose(candidate, firstFiles, continuation));
        } finally {
            firstFiles.close();
        }
        require(
                firstProposal instanceof DaemonProposalResult.Rejected,
                "first proposal was " + firstProposal);
        require(
                "metadata-only activation is not available"
                        .equals(((DaemonProposalResult.Rejected) firstProposal).getReason()),
                "first proposal rejection differs");

        OtaCandidateFiles replayFiles = candidateFiles(candidateDirectory);
        DaemonProposalResult replayProposal;
        try {
            replayProposal =
                    await(
                            DaemonProposalResult.class,
                            continuation -> gateway.propose(candidate, replayFiles, continuation));
        } finally {
            replayFiles.close();
        }
        require(firstProposal.equals(replayProposal), "proposal replay changed result");

        DaemonActiveResult second =
                await(
                        DaemonActiveResult.class,
                        continuation -> gateway.activeRelease(continuation));
        require(second instanceof DaemonActiveResult.Active, "second activeRelease was " + second);
        require(
                identity.equals(((DaemonActiveResult.Active) second).getIdentity()),
                "active release changed");
        return "active-release-and-descriptor-replay-ok";
    }

    private static String rawActiveRelease() throws Exception {
        byte[] request =
                "{\"request\":\"activeRelease\"}\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        LocalSocket socket = connectRawWithDeadline();
        try {
            socket.setSoTimeout(2_000);
            socket.getOutputStream()
                    .write(
                            new byte[] {
                                (byte) (request.length >>> 24),
                                (byte) (request.length >>> 16),
                                (byte) (request.length >>> 8),
                                (byte) request.length,
                            });
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            InputStream input = socket.getInputStream();
            byte[] header = readExact(input, 4);
            int length =
                    ((header[0] & 0xff) << 24)
                            | ((header[1] & 0xff) << 16)
                            | ((header[2] & 0xff) << 8)
                            | (header[3] & 0xff);
            require(length > 0 && length <= 524_288, "raw response length differs");
            return new String(readExact(input, length), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            socket.close();
        }
    }

    private static LocalSocket connectRawWithDeadline() throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 2_000;
        java.io.IOException lastFailure = null;
        do {
            LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_STREAM);
            try {
                socket.connect(
                        new LocalSocketAddress(
                                "voboost.ota.v1", LocalSocketAddress.Namespace.ABSTRACT));
                return socket;
            } catch (java.io.IOException failure) {
                lastFailure = failure;
                try {
                    socket.close();
                } catch (java.io.IOException ignored) {
                    // The connection failure remains authoritative.
                }
                if (android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(25);
                }
            }
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new java.io.IOException(
                "daemon socket did not become ready within two seconds", lastFailure);
    }

    private static byte[] readExact(InputStream input, int size) throws Exception {
        byte[] result = new byte[size];
        int offset = 0;
        while (offset < size) {
            int read = input.read(result, offset, size - offset);
            if (read < 0) throw new java.io.EOFException("raw daemon response disconnected");
            if (read == 0) throw new IllegalStateException("raw daemon response made no progress");
            offset += read;
        }
        return result;
    }

    private OtaCandidateFiles candidateFiles(File directory) throws Exception {
        List<AppReadAuthority> authorities = new ArrayList<>();
        List<RetainedOtaFile> retained = new ArrayList<>();
        for (String name : List.of("voboost.json", "voboost.json.sig")) {
            byte[] bytes = Files.readAllBytes(new File(directory, name).toPath());
            AppDirectoryAuthority parent;
            try {
                parent = AppFileAuthorityKt.getPlatformAppFileOps().openPrivateDirectory(directory);
            } catch (Throwable failure) {
                throw new IllegalStateException(
                        "Android directory open flags: " + probeDirectoryFlags(directory), failure);
            }
            AppReadAuthority authority;
            try {
                authority = parent.openRead(name);
            } finally {
                parent.close();
            }
            authorities.add(authority);
            AppFileIdentity identity = authority.identity();
            retained.add(
                    new RetainedOtaFile(name, bytes.length, sha256(bytes), identity, authority));
        }
        return new OtaCandidateFiles(
                retained,
                new Function0<Unit>() {
                    @Override
                    public Unit invoke() {
                        for (int index = authorities.size() - 1; index >= 0; index--) {
                            try {
                                authorities.get(index).close();
                            } catch (Exception ignored) {
                                // The transport result is authoritative; descriptor close is
                                // best-effort here.
                            }
                        }
                        return Unit.INSTANCE;
                    }
                });
    }

    private static String probeDirectoryFlags(File directory) {
        StringBuilder report = new StringBuilder();
        int[] flags = {
            OsConstants.O_RDONLY,
            OsConstants.O_RDONLY | OsConstants.O_CLOEXEC,
            OsConstants.O_RDONLY | 65_536,
            OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW,
            OsConstants.O_RDONLY | 65_536 | OsConstants.O_CLOEXEC,
            OsConstants.O_RDONLY | 65_536 | OsConstants.O_NOFOLLOW,
            OsConstants.O_RDONLY | 65_536 | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW,
        };
        for (int value : flags) {
            java.io.FileDescriptor descriptor = null;
            try {
                descriptor = Os.open(directory.getPath(), value, 0);
                report.append(value).append("=ok;");
            } catch (ErrnoException error) {
                report.append(value).append("=errno").append(error.errno).append(';');
            } finally {
                if (descriptor != null) {
                    try {
                        Os.close(descriptor);
                    } catch (ErrnoException ignored) {
                        // Probe cleanup only.
                    }
                }
            }
        }
        return report.toString();
    }

    private interface SuspendCall<T> {
        Object invoke(Continuation<? super T> continuation) throws Exception;
    }

    private static <T> T await(Class<T> expected, SuspendCall<T> call) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Object> resumed = new AtomicReference<>();
        Continuation<T> continuation =
                new Continuation<T>() {
                    @Override
                    public CoroutineContext getContext() {
                        return EmptyCoroutineContext.INSTANCE;
                    }

                    @Override
                    public void resumeWith(Object result) {
                        resumed.set(result);
                        completed.countDown();
                    }
                };
        Object immediate = call.invoke(continuation);
        if (expected.isInstance(immediate)) {
            return expected.cast(immediate);
        }
        require(completed.await(10, TimeUnit.SECONDS), "suspend call exceeded 10 seconds");
        Object result = resumed.get();
        if (expected.isInstance(result)) {
            return expected.cast(result);
        }
        if (result != null && "kotlin.Result$Failure".equals(result.getClass().getName())) {
            java.lang.reflect.Field exception = result.getClass().getDeclaredField("exception");
            exception.setAccessible(true);
            Throwable failure = (Throwable) exception.get(result);
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException(failure);
        }
        throw new IllegalStateException("suspend call returned unexpected value " + result);
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
