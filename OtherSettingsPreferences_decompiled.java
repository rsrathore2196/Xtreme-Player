/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.content.Context
 *  android.content.SharedPreferences
 *  android.content.SharedPreferences$Editor
 *  android.content.SharedPreferences$OnSharedPreferenceChangeListener
 *  androidx.compose.runtime.internal.StabilityInferred
 *  kotlin.Metadata
 *  kotlin.ResultKt
 *  kotlin.Unit
 *  kotlin.collections.CollectionsKt
 *  kotlin.collections.SetsKt
 *  kotlin.coroutines.Continuation
 *  kotlin.coroutines.CoroutineContext
 *  kotlin.coroutines.intrinsics.IntrinsicsKt
 *  kotlin.coroutines.jvm.internal.ContinuationImpl
 *  kotlin.coroutines.jvm.internal.SpillingKt
 *  kotlin.jvm.JvmField
 *  kotlin.jvm.functions.Function2
 *  kotlin.jvm.internal.Intrinsics
 *  kotlin.jvm.internal.SourceDebugExtension
 *  kotlin.ranges.RangesKt
 *  kotlin.text.StringsKt
 *  kotlinx.coroutines.BuildersKt
 *  kotlinx.coroutines.CoroutineDispatcher
 *  kotlinx.coroutines.CoroutineScope
 *  kotlinx.coroutines.CoroutineScopeKt
 *  kotlinx.coroutines.Dispatchers
 *  kotlinx.coroutines.SupervisorKt
 *  kotlinx.coroutines.channels.ProduceKt
 *  kotlinx.coroutines.channels.ProducerScope
 *  kotlinx.coroutines.flow.Flow
 *  kotlinx.coroutines.flow.FlowCollector
 *  kotlinx.coroutines.flow.FlowKt
 *  kotlinx.coroutines.flow.MutableStateFlow
 *  kotlinx.coroutines.flow.StateFlow
 *  kotlinx.coroutines.flow.StateFlowKt
 *  org.jetbrains.annotations.NotNull
 *  org.jetbrains.annotations.Nullable
 */
package com.example.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.compose.runtime.internal.StabilityInferred;
import com.example.data.local.LyricsSettings;
import com.example.data.local.OtherSettingsPreferences;
import com.example.data.local.OtherSettingsPreferences$observePlaybackIntegrations$;
import com.example.data.local.OtherSettingsPreferences$observePlayerExperience$;
import com.example.data.local.PlaybackIntegrationSettings;
import com.example.data.local.PlayerExperienceSettings;
import com.example.data.local.ReactiveSettingsRepository;
import com.example.performance.AppDispatchers;
import com.example.playback.OutputPrecision;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Metadata;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.collections.SetsKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlin.coroutines.jvm.internal.SpillingKt;
import kotlin.jvm.JvmField;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.SourceDebugExtension;
import kotlin.ranges.RangesKt;
import kotlin.text.StringsKt;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.SupervisorKt;
import kotlinx.coroutines.channels.ProduceKt;
import kotlinx.coroutines.channels.ProducerScope;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowCollector;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Metadata(mv={2, 4, 0}, k=1, xi=48, d1={"\u0000\u00a8\u0001\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0002\b\t\n\u0002\u0010 \n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0002\b\u0003\n\u0002\u0010\b\n\u0002\b\u0010\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0002\b\b\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0010\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0010\u0007\n\u0002\b\f\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\b\n\u0002\u0018\u0002\n\u0000\b\u00c7\u0002\u0018\u00002\u00020\u0001:\u0001{B\t\b\u0002\u00a2\u0006\u0004\b\u0002\u0010\u0003J\u0014\u0010\u001b\u001a\b\u0012\u0004\u0012\u00020\u00180\u001c2\u0006\u0010\u001d\u001a\u00020\u001eJ\u000e\u0010\u001f\u001a\u00020\u00052\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010 \u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010\"\u001a\u00020\u0005J\u0016\u0010#\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010$\u001a\u00020%J\u000e\u00105\u001a\u0002062\u0006\u0010\u001d\u001a\u00020\u001eJ\u0014\u00107\u001a\b\u0012\u0004\u0012\u000206082\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u00109\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010:\u001a\u00020;J\u0016\u0010<\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010:\u001a\u00020;J\u001e\u0010=\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010>\u001a\u00020\u00052\u0006\u0010?\u001a\u00020\u0005J\u000e\u0010@\u001a\u00020A2\u0006\u0010\u001d\u001a\u00020\u001eJ\u0014\u0010B\u001a\b\u0012\u0004\u0012\u00020A082\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010C\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010:\u001a\u00020;J\u0016\u0010D\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010:\u001a\u00020;J\u0016\u0010E\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010:\u001a\u00020;J\u0016\u0010F\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010:\u001a\u00020;J\u0016\u0010G\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010:\u001a\u00020;J\u0016\u0010H\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010I\u001a\u00020JJ\u0010\u0010K\u001a\u00020L2\u0006\u0010\u001d\u001a\u00020\u001eH\u0002J\u000e\u0010M\u001a\u00020;2\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010N\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010:\u001a\u00020;J\u000e\u0010O\u001a\u00020;2\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010P\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010:\u001a\u00020;J\u000e\u0010Q\u001a\u00020\u00052\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010R\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010S\u001a\u00020\u0005J\u000e\u0010T\u001a\u00020%2\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010U\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010V\u001a\u00020%J\u000e\u0010W\u001a\u00020\u00052\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010X\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010Y\u001a\u00020\u0005J\u000e\u0010Z\u001a\u00020;2\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010[\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010:\u001a\u00020;J\u000e\u0010`\u001a\u00020%2\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010a\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010b\u001a\u00020%J\u000e\u0010c\u001a\u00020d2\u0006\u0010\u001d\u001a\u00020\u001eJ\u000e\u0010h\u001a\u00020%2\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010i\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010b\u001a\u00020%J\u000e\u0010j\u001a\u00020d2\u0006\u0010\u001d\u001a\u00020\u001eJ\u000e\u0010k\u001a\u00020%2\u0006\u0010\u001d\u001a\u00020\u001eJ\u000e\u0010l\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001eJ\u000e\u0010m\u001a\u00020%2\u0006\u0010\u001d\u001a\u00020\u001eJ\u0016\u0010n\u001a\u00020!2\u0006\u0010\u001d\u001a\u00020\u001e2\u0006\u0010o\u001a\u00020%J\u000e\u0010p\u001a\u00020q2\u0006\u0010\u001d\u001a\u00020\u001eJ\u0012\u0010r\u001a\u00020q2\b\u0010s\u001a\u0004\u0018\u00010tH\u0002J\u000e\u0010u\u001a\u00020\u00052\u0006\u0010v\u001a\u00020qJ\u000e\u0010w\u001a\u00020;2\u0006\u0010\u001d\u001a\u00020\u001eJ\u0012\u0010x\u001a\u00020!2\b\u0010y\u001a\u0004\u0018\u00010tH\u0002J\u0012\u0010z\u001a\u00020;2\b\u0010y\u001a\u0004\u0018\u00010tH\u0002R\u000e\u0010\u0004\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0007\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\t\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\n\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000b\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\f\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\r\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u0017\u0010\u000e\u001a\b\u0012\u0004\u0012\u00020\u00050\u000f\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0010\u0010\u0011R\u000e\u0010\u0012\u001a\u00020\u0013X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0014\u001a\u00020\u0015X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0014\u0010\u0016\u001a\b\u0012\u0004\u0012\u00020\u00180\u0017X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u0019\u001a\u0004\u0018\u00010\u001aX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010&\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010'\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010(\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010)\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010*\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010+\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010,\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010-\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010.\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u0010/\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u00100\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u00101\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u00102\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u00103\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u000e\u00104\u001a\u00020\u0005X\u0082T\u00a2\u0006\u0002\n\u0000R\u0017\u0010\\\u001a\b\u0012\u0004\u0012\u00020]0\u000f\u00a2\u0006\b\n\u0000\u001a\u0004\b^\u0010\u0011R\u000e\u0010_\u001a\u00020%X\u0086T\u00a2\u0006\u0002\n\u0000R\u0017\u0010e\u001a\b\u0012\u0004\u0012\u00020]0\u000f\u00a2\u0006\b\n\u0000\u001a\u0004\bf\u0010\u0011R\u000e\u0010g\u001a\u00020%X\u0086T\u00a2\u0006\u0002\n\u0000\u00ca\u0001\f\b}\u0012\b\b~\u0012\u0004\b\u0003\u0010\u0002\u00a8\u0006|"}, d2={"Lcom/example/data/local/OtherSettingsPreferences;", "", "<init>", "()V", "PREFS_NAME", "", "KEY_PROXY_ENABLED", "KEY_PROXY_HOST", "KEY_PROXY_PORT", "KEY_PROXY_TYPE", "KEY_TEXT_SIZE_INDEX", "KEY_UI_SIZE_INDEX", "KEY_LYRICS_PROVIDER", "KEY_LYRICS_TEXT_SIZE", "LYRICS_PROVIDERS", "", "getLYRICS_PROVIDERS", "()Ljava/util/List;", "lyricsScope", "Lkotlinx/coroutines/CoroutineScope;", "lyricsStarted", "Ljava/util/concurrent/atomic/AtomicBoolean;", "lyricsState", "Lkotlinx/coroutines/flow/MutableStateFlow;", "Lcom/example/data/local/LyricsSettings;", "lyricsListener", "Landroid/content/SharedPreferences$OnSharedPreferenceChangeListener;", "observeLyricsSettings", "Lkotlinx/coroutines/flow/StateFlow;", "context", "Landroid/content/Context;", "getLyricsProvider", "setLyricsProvider", "", "provider", "setLyricsTextSize", "sizeSp", "", "KEY_TOTAL_PLAYED_COUNT", "KEY_TOTAL_LISTENING_MINUTES", "KEY_CACHE_CLEARED_TIMESTAMP", "KEY_HAPTICS_ENABLED", "KEY_DYNAMIC_GLASS_ENABLED", "KEY_ALBUM_GLOW", "KEY_HIGH_CONTRAST", "KEY_CROSSFADE", "KEY_SPATIAL_AUDIO", "KEY_SKIP_SILENCE", "KEY_OUTPUT_PRECISION", "KEY_KEEP_LYRICS_AWAKE", "KEY_ADDON_ENABLED", "KEY_ADDON_URL", "KEY_ADDON_NAME", "playbackIntegrations", "Lcom/example/data/local/PlaybackIntegrationSettings;", "observePlaybackIntegrations", "Lkotlinx/coroutines/flow/Flow;", "setKeepLyricsAwake", "enabled", "", "setStreamingAddonEnabled", "saveStreamingAddon", "url", "name", "playerExperienceSettings", "Lcom/example/data/local/PlayerExperienceSettings;", "observePlayerExperience", "setDynamicAlbumGlow", "setHighContrastTypography", "setCrossfade", "setSpatialAudio", "setSkipSilence", "setOutputPrecision", "precision", "Lcom/example/playback/OutputPrecision;", "getPrefs", "Landroid/content/SharedPreferences;", "isDynamicGlassEnabled", "setDynamicGlassEnabled", "isProxyEnabled", "setProxyEnabled", "getProxyHost", "setProxyHost", "host", "getProxyPort", "setProxyPort", "port", "getProxyType", "setProxyType", "type", "isHapticsEnabled", "setHapticsEnabled", "TEXT_SIZE_OPTIONS", "Lcom/example/data/local/OtherSettingsPreferences$ScaleOption;", "getTEXT_SIZE_OPTIONS", "DEFAULT_TEXT_SIZE_INDEX", "getTextSizeIndex", "setTextSizeIndex", "index", "getTextScale", "", "UI_SIZE_OPTIONS", "getUI_SIZE_OPTIONS", "DEFAULT_UI_SIZE_INDEX", "getUiSizeIndex", "setUiSizeIndex", "getUiScale", "getTotalPlayedCount", "incrementPlayedCount", "getTotalListeningMinutes", "addListeningMinutes", "minutes", "calculateCacheSizeBytes", "", "getFolderSize", "file", "Ljava/io/File;", "formatBytes", "bytes", "clearCache", "clearCacheDir", "dir", "deleteDir", "ScaleOption", "app", "Landroidx/compose/runtime/internal/StabilityInferred;", "parameters"})
@StabilityInferred(parameters=1)
@SourceDebugExtension(value={"SMAP\nOtherSettingsPreferences.kt\nKotlin\n*S Kotlin\n*F\n+ 1 OtherSettingsPreferences.kt\ncom/example/data/local/OtherSettingsPreferences\n+ 2 fake.kt\nkotlin/jvm/internal/FakeKt\n+ 3 Transform.kt\nkotlinx/coroutines/flow/FlowKt__TransformKt\n+ 4 Emitters.kt\nkotlinx/coroutines/flow/FlowKt__EmittersKt\n+ 5 SafeCollector.common.kt\nkotlinx/coroutines/flow/internal/SafeCollector_commonKt\n+ 6 _Arrays.kt\nkotlin/collections/ArraysKt___ArraysKt\n*L\n1#1,384:1\n1#2:385\n49#3:386\n51#3:390\n49#3:391\n51#3:395\n45#4:387\n49#4:389\n45#4:392\n49#4:394\n105#5:388\n105#5:393\n16144#6,2:396\n*S KotlinDebug\n*F\n+ 1 OtherSettingsPreferences.kt\ncom/example/data/local/OtherSettingsPreferences\n*L\n120#1:386\n120#1:390\n149#1:391\n149#1:395\n120#1:387\n120#1:389\n149#1:392\n149#1:394\n120#1:388\n149#1:393\n359#1:396,2\n*E\n"})
public final class OtherSettingsPreferences {
    @NotNull
    public static final OtherSettingsPreferences INSTANCE = new OtherSettingsPreferences();
    @NotNull
    private static final String PREFS_NAME = "xtreme_other_settings";
    @NotNull
    private static final String KEY_PROXY_ENABLED = "proxy_enabled";
    @NotNull
    private static final String KEY_PROXY_HOST = "proxy_host";
    @NotNull
    private static final String KEY_PROXY_PORT = "proxy_port";
    @NotNull
    private static final String KEY_PROXY_TYPE = "proxy_type";
    @NotNull
    private static final String KEY_TEXT_SIZE_INDEX = "text_size_index";
    @NotNull
    private static final String KEY_UI_SIZE_INDEX = "ui_size_index";
    @NotNull
    private static final String KEY_LYRICS_PROVIDER = "lyrics_provider";
    @NotNull
    private static final String KEY_LYRICS_TEXT_SIZE = "lyrics_text_size_sp";
    @NotNull
    private static final List<String> LYRICS_PROVIDERS;
    @NotNull
    private static final CoroutineScope lyricsScope;
    @NotNull
    private static final AtomicBoolean lyricsStarted;
    @NotNull
    private static final MutableStateFlow<LyricsSettings> lyricsState;
    @Nullable
    private static SharedPreferences.OnSharedPreferenceChangeListener lyricsListener;
    @NotNull
    private static final String KEY_TOTAL_PLAYED_COUNT = "total_played_count";
    @NotNull
    private static final String KEY_TOTAL_LISTENING_MINUTES = "total_listening_minutes";
    @NotNull
    private static final String KEY_CACHE_CLEARED_TIMESTAMP = "cache_cleared_timestamp";
    @NotNull
    private static final String KEY_HAPTICS_ENABLED = "haptics_enabled";
    @NotNull
    private static final String KEY_DYNAMIC_GLASS_ENABLED = "dynamic_glass_enabled";
    @NotNull
    private static final String KEY_ALBUM_GLOW = "player_dynamic_album_glow";
    @NotNull
    private static final String KEY_HIGH_CONTRAST = "player_high_contrast_typography";
    @NotNull
    private static final String KEY_CROSSFADE = "playback_crossfade_enabled";
    @NotNull
    private static final String KEY_SPATIAL_AUDIO = "playback_spatial_audio";
    @NotNull
    private static final String KEY_SKIP_SILENCE = "playback_skip_silence";
    @NotNull
    private static final String KEY_OUTPUT_PRECISION = "playback_output_precision";
    @NotNull
    private static final String KEY_KEEP_LYRICS_AWAKE = "keep_lyrics_screen_awake";
    @NotNull
    private static final String KEY_ADDON_ENABLED = "streaming_addon_enabled";
    @NotNull
    private static final String KEY_ADDON_URL = "streaming_addon_url";
    @NotNull
    private static final String KEY_ADDON_NAME = "streaming_addon_name";
    @NotNull
    private static final List<ScaleOption> TEXT_SIZE_OPTIONS;
    public static final int DEFAULT_TEXT_SIZE_INDEX = 3;
    @NotNull
    private static final List<ScaleOption> UI_SIZE_OPTIONS;
    public static final int DEFAULT_UI_SIZE_INDEX = 3;
    @JvmField
    public static final int $stable;

    private OtherSettingsPreferences() {
    }

    @NotNull
    public final List<String> getLYRICS_PROVIDERS() {
        return LYRICS_PROVIDERS;
    }

    @NotNull
    public final StateFlow<LyricsSettings> observeLyricsSettings(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        if (lyricsStarted.compareAndSet(false, true)) {
            BuildersKt.launch$default((CoroutineScope)lyricsScope, null, null, (Function2)((Function2)new Function2<CoroutineScope, Continuation<? super Unit>, Object>(context, null){
                int label;
                final /* synthetic */ Context $context;
                {
                    this.$context = $context;
                    super(2, $completion);
                }

                public final Object invokeSuspend(Object $result) {
                    IntrinsicsKt.getCOROUTINE_SUSPENDED();
                    switch (this.label) {
                        case 0: {
                            SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener;
                            ResultKt.throwOnFailure((Object)$result);
                            Context context = this.$context.getApplicationContext();
                            Intrinsics.checkNotNullExpressionValue((Object)context, (String)"getApplicationContext(...)");
                            SharedPreferences prefs = OtherSettingsPreferences.access$getPrefs(OtherSettingsPreferences.INSTANCE, context);
                            SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener2 = onSharedPreferenceChangeListener = (arg_0, arg_1) -> observeLyricsSettings.1.invokeSuspend$lambda$1(prefs, arg_0, arg_1);
                            boolean bl = false;
                            prefs.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener2);
                            OtherSettingsPreferences.access$setLyricsListener$p(onSharedPreferenceChangeListener);
                            observeLyricsSettings.1.invokeSuspend$publish(prefs);
                            return Unit.INSTANCE;
                        }
                    }
                    throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
                }

                public final Continuation<Unit> create(Object value, Continuation<?> $completion) {
                    return (Continuation)new /* invalid duplicate definition of identical inner class */;
                }

                public final Object invoke(CoroutineScope p1, Continuation<? super Unit> p2) {
                    return (this.create(p1, p2)).invokeSuspend(Unit.INSTANCE);
                }

                /*
                 * Unable to fully structure code
                 */
                private static final void invokeSuspend$publish(SharedPreferences prefs) {
                    v0 = OtherSettingsPreferences.access$getLyricsState$p();
                    var1_1 = prefs.getString("lyrics_provider", "Auto");
                    if (var1_1 == null) ** GOTO lbl-1000
                    var4_3 = var3_2 = var1_1;
                    var6_4 = v0;
                    $i$a$-takeIf-OtherSettingsPreferences$observeLyricsSettings$1$publish$1\1\67\0 = false;
                    var7_6 = OtherSettingsPreferences.INSTANCE.getLYRICS_PROVIDERS().contains(it\1);
                    v0 = var6_4;
                    v1 = var2_7 = var7_6 != false ? var3_2 : null;
                    if (var2_7 != null) {
                        v2 = var2_7;
                    } else lbl-1000:
                    // 2 sources

                    {
                        v2 = "Auto";
                    }
                    var8_8 = true;
                    var9_9 = RangesKt.coerceIn((int)prefs.getInt("lyrics_text_size_sp", 32), (int)18, (int)44);
                    var10_10 = v2;
                    v0.setValue((Object)new LyricsSettings(var10_10, var9_9, var8_8));
                }

                /*
                 * Unable to fully structure code
                 */
                private static final void invokeSuspend$lambda$1(SharedPreferences $prefs, SharedPreferences var1_1, String key) {
                    block5: {
                        if (key == null) break block5;
                        switch (key.hashCode()) {
                            case -861829964: {
                                if (!key.equals("lyrics_text_size_sp")) {
                                    break;
                                }
                                ** GOTO lbl9
                            }
                            case 613614044: {
                                if (!key.equals("lyrics_provider")) break;
lbl9:
                                // 2 sources

                                observeLyricsSettings.1.invokeSuspend$publish($prefs);
                            }
                        }
                    }
                }
            }), (int)3, null);
        }
        return FlowKt.asStateFlow(lyricsState);
    }

    /*
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    @NotNull
    public final String getLyricsProvider(@NotNull Context context) {
        String string;
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        String string2 = this.getPrefs(context).getString(KEY_LYRICS_PROVIDER, "Auto");
        if (string2 == null) return "Auto";
        String string3 = string = string2;
        boolean bl = false;
        if (!LYRICS_PROVIDERS.contains(string3)) return "Auto";
        String string4 = string;
        String string5 = string4;
        if (string5 == null) return "Auto";
        String string6 = string5;
        return string6;
    }

    public final void setLyricsProvider(@NotNull Context context, @NotNull String provider) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        Intrinsics.checkNotNullParameter((Object)provider, (String)"provider");
        if (!LYRICS_PROVIDERS.contains(provider)) {
            return;
        }
        Context app = context.getApplicationContext();
        Intrinsics.checkNotNull((Object)app);
        this.observeLyricsSettings(app);
        BuildersKt.launch$default((CoroutineScope)lyricsScope, null, null, (Function2)((Function2)new Function2<CoroutineScope, Continuation<? super Unit>, Object>(app, provider, null){
            int label;
            final /* synthetic */ Context $app;
            final /* synthetic */ String $provider;
            {
                this.$app = $app;
                this.$provider = $provider;
                super(2, $completion);
            }

            public final Object invokeSuspend(Object $result) {
                IntrinsicsKt.getCOROUTINE_SUSPENDED();
                switch (this.label) {
                    case 0: {
                        ResultKt.throwOnFailure((Object)$result);
                        Context context = this.$app;
                        Intrinsics.checkNotNull((Object)context);
                        OtherSettingsPreferences.access$getPrefs(OtherSettingsPreferences.INSTANCE, context).edit().putString("lyrics_provider", this.$provider).apply();
                        return Unit.INSTANCE;
                    }
                }
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }

            public final Continuation<Unit> create(Object value, Continuation<?> $completion) {
                return (Continuation)new /* invalid duplicate definition of identical inner class */;
            }

            public final Object invoke(CoroutineScope p1, Continuation<? super Unit> p2) {
                return (this.create(p1, p2)).invokeSuspend(Unit.INSTANCE);
            }
        }), (int)3, null);
    }

    public final void setLyricsTextSize(@NotNull Context context, int sizeSp) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        Context app = context.getApplicationContext();
        Intrinsics.checkNotNull((Object)app);
        this.observeLyricsSettings(app);
        BuildersKt.launch$default((CoroutineScope)lyricsScope, null, null, (Function2)((Function2)new Function2<CoroutineScope, Continuation<? super Unit>, Object>(app, sizeSp, null){
            int label;
            final /* synthetic */ Context $app;
            final /* synthetic */ int $sizeSp;
            {
                this.$app = $app;
                this.$sizeSp = $sizeSp;
                super(2, $completion);
            }

            public final Object invokeSuspend(Object $result) {
                IntrinsicsKt.getCOROUTINE_SUSPENDED();
                switch (this.label) {
                    case 0: {
                        ResultKt.throwOnFailure((Object)$result);
                        Context context = this.$app;
                        Intrinsics.checkNotNull((Object)context);
                        OtherSettingsPreferences.access$getPrefs(OtherSettingsPreferences.INSTANCE, context).edit().putInt("lyrics_text_size_sp", RangesKt.coerceIn((int)this.$sizeSp, (int)18, (int)44)).apply();
                        return Unit.INSTANCE;
                    }
                }
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }

            public final Continuation<Unit> create(Object value, Continuation<?> $completion) {
                return (Continuation)new /* invalid duplicate definition of identical inner class */;
            }

            public final Object invoke(CoroutineScope p1, Continuation<? super Unit> p2) {
                return (this.create(p1, p2)).invokeSuspend(Unit.INSTANCE);
            }
        }), (int)3, null);
    }

    @NotNull
    public final PlaybackIntegrationSettings playbackIntegrations(@NotNull Context context) {
        String string;
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        SharedPreferences sharedPreferences = this.getPrefs(context);
        boolean bl = false;
        boolean bl2 = sharedPreferences.getBoolean(KEY_KEEP_LYRICS_AWAKE, false);
        boolean bl3 = sharedPreferences.getBoolean(KEY_ADDON_ENABLED, false);
        String string2 = sharedPreferences.getString(KEY_ADDON_URL, "");
        if (string2 == null) {
            string2 = "";
        }
        if ((string = sharedPreferences.getString(KEY_ADDON_NAME, "")) == null) {
            string = "";
        }
        return new PlaybackIntegrationSettings(bl2, bl3, string2, string);
    }

    @NotNull
    public final Flow<PlaybackIntegrationSettings> observePlaybackIntegrations(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        Flow flow = FlowKt.conflate((Flow)FlowKt.callbackFlow((Function2)((Function2)new Function2<ProducerScope<? super Unit>, Continuation<? super Unit>, Object>(context, null){
            Object L$1;
            Object L$2;
            int label;
            private /* synthetic */ Object L$0;
            final /* synthetic */ Context $context;
            {
                this.$context = $context;
                super(2, $completion);
            }

            /*
             * WARNING - void declaration
             * Enabled force condition propagation
             * Lifted jumps to return sites
             */
            public final Object invokeSuspend(Object $result) {
                ProducerScope producerScope = (ProducerScope)this.L$0;
                Object object = IntrinsicsKt.getCOROUTINE_SUSPENDED();
                switch (this.label) {
                    case 0: {
                        void $this$callbackFlow;
                        ResultKt.throwOnFailure((Object)$result);
                        Context context = this.$context.getApplicationContext();
                        Intrinsics.checkNotNullExpressionValue((Object)context, (String)"getApplicationContext(...)");
                        SharedPreferences prefs = OtherSettingsPreferences.access$getPrefs(OtherSettingsPreferences.INSTANCE, context);
                        SharedPreferences.OnSharedPreferenceChangeListener listener = (arg_0, arg_1) -> observePlaybackIntegrations.1.invokeSuspend$lambda$0((ProducerScope)$this$callbackFlow, arg_0, arg_1);
                        prefs.registerOnSharedPreferenceChangeListener(listener);
                        $this$callbackFlow.trySend-JP2dKIU((Object)Unit.INSTANCE);
                        this.L$0 = SpillingKt.nullOutSpilledVariable((Object)$this$callbackFlow);
                        this.L$1 = SpillingKt.nullOutSpilledVariable((Object)prefs);
                        this.L$2 = SpillingKt.nullOutSpilledVariable((Object)listener);
                        this.label = 1;
                        Object object2 = ProduceKt.awaitClose((ProducerScope)$this$callbackFlow, () -> observePlaybackIntegrations.1.invokeSuspend$lambda$1(prefs, listener), (Continuation)((Continuation)this));
                        if (object2 != object) return Unit.INSTANCE;
                        return object;
                    }
                    case 1: {
                        SharedPreferences.OnSharedPreferenceChangeListener listener = (SharedPreferences.OnSharedPreferenceChangeListener)this.L$2;
                        SharedPreferences prefs = (SharedPreferences)this.L$1;
                        ResultKt.throwOnFailure((Object)$result);
                        Object object2 = $result;
                        return Unit.INSTANCE;
                    }
                }
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }

            public final Continuation<Unit> create(Object value, Continuation<?> $completion) {
                var var3_3 = new /* invalid duplicate definition of identical inner class */;
                var3_3.L$0 = value;
                return (Continuation)var3_3;
            }

            public final Object invoke(ProducerScope<? super Unit> p1, Continuation<? super Unit> p2) {
                return (this.create(p1, p2)).invokeSuspend(Unit.INSTANCE);
            }

            private static final void invokeSuspend$lambda$0(ProducerScope $$this$callbackFlow, SharedPreferences sharedPreferences, String key) {
                block3: {
                    block2: {
                        if (key == null) break block2;
                        Object[] objectArray = new String[]{"keep_lyrics_screen_awake", "streaming_addon_enabled", "streaming_addon_url", "streaming_addon_name"};
                        if (!SetsKt.setOf((Object[])objectArray).contains(key)) break block3;
                    }
                    $$this$callbackFlow.trySend-JP2dKIU((Object)Unit.INSTANCE);
                }
            }

            private static final Unit invokeSuspend$lambda$1(SharedPreferences $prefs, SharedPreferences.OnSharedPreferenceChangeListener $listener) {
                $prefs.unregisterOnSharedPreferenceChangeListener($listener);
                return Unit.INSTANCE;
            }
        })));
        boolean bl = false;
        Flow flow2 = flow;
        boolean bl2 = false;
        boolean bl3 = false;
        return FlowKt.flowOn((Flow)FlowKt.distinctUntilChanged((Flow)((Flow)new Flow<PlaybackIntegrationSettings>(flow2, context){
            final /* synthetic */ Flow $this_unsafeTransform$inlined;
            final /* synthetic */ Context $context$inlined;
            {
                this.$this_unsafeTransform$inlined = flow;
                this.$context$inlined = context;
            }

            /*
             * Unable to fully structure code
             */
            public Object collect(FlowCollector collector, Continuation $completion) {
                if (!($completion instanceof observePlaybackIntegrations$$inlined$map$1$1)) ** GOTO lbl-1000
                var3_3 = $completion;
                if ((var3_3.label & -2147483648) != 0) {
                    var3_3.label -= -2147483648;
                } else lbl-1000:
                // 2 sources

                {
                    $continuation = new ContinuationImpl(this, $completion){
                        /* synthetic */ Object result;
                        int label;
                        final /* synthetic */ observePlaybackIntegrations$$inlined$map$1 this$0;
                        Object L$0;
                        Object L$1;
                        Object L$2;
                        {
                            this.this$0 = this$0;
                            super($completion);
                        }

                        public final Object invokeSuspend(Object $result) {
                            this.result = $result;
                            this.label |= Integer.MIN_VALUE;
                            return this.this$0.collect(null, (Continuation)this);
                        }
                    };
                }
                $result = $continuation.result;
                var5_5 = IntrinsicsKt.getCOROUTINE_SUSPENDED();
                switch ($continuation.label) {
                    case 0: {
                        ResultKt.throwOnFailure((Object)$result);
                        var6_6 = $continuation;
                        $this$unsafeTransform_u24lambda_u240 = collector;
                        $i$a$-unsafeFlow-FlowKt__EmittersKt$unsafeTransform$1\1\107\0 = false;
                        $continuation.L$0 = SpillingKt.nullOutSpilledVariable((Object)collector);
                        $continuation.L$1 = SpillingKt.nullOutSpilledVariable((Object)$completion);
                        $continuation.L$2 = SpillingKt.nullOutSpilledVariable((Object)$this$unsafeTransform_u24lambda_u240);
                        $continuation.label = 1;
                        v0 = this.$this_unsafeTransform$inlined.collect(new FlowCollector($this$unsafeTransform_u24lambda_u240, this.$context$inlined){
                            final /* synthetic */ FlowCollector $this_unsafeFlow;
                            final /* synthetic */ Context $context$inlined;
                            {
                                this.$this_unsafeFlow = $receiver;
                                this.$context$inlined = context;
                            }

                            /*
                             * Unable to fully structure code
                             */
                            public final Object emit(Object value, Continuation $completion) {
                                if (!($completion instanceof observePlaybackIntegrations$$inlined$map$1$2$1)) ** GOTO lbl-1000
                                var3_3 = $completion;
                                if ((var3_3.label & -2147483648) != 0) {
                                    var3_3.label -= -2147483648;
                                } else lbl-1000:
                                // 2 sources

                                {
                                    $continuation = new ContinuationImpl(this, $completion){
                                        Object L$0;
                                        /* synthetic */ Object result;
                                        int label;
                                        Object L$1;
                                        Object L$2;
                                        Object L$3;
                                        Object L$4;
                                        int I$0;
                                        final /* synthetic */ observePlaybackIntegrations$$inlined$map$1$2 this$0;
                                        {
                                            this.this$0 = this$0;
                                            super($completion);
                                        }

                                        public final Object invokeSuspend(Object $result) {
                                            this.result = $result;
                                            this.label |= Integer.MIN_VALUE;
                                            return this.this$0.emit(null, (Continuation)this);
                                        }
                                    };
                                }
                                $result = $continuation.result;
                                var5_5 = IntrinsicsKt.getCOROUTINE_SUSPENDED();
                                switch ($continuation.label) {
                                    case 0: {
                                        ResultKt.throwOnFailure((Object)$result);
                                        var6_6 = $continuation;
                                        var7_8 = value;
                                        $this$map_u24lambda_u240 = this.$this_unsafeFlow;
                                        $i$a$-unsafeTransform-FlowKt__TransformKt$map$1\1\47\0 = false;
                                        var10_14 = $this$map_u24lambda_u240;
                                        var11_15 = (Continuation)$continuation;
                                        it\1 = (Unit)value;
                                        $i$a$-map-OtherSettingsPreferences$observePlaybackIntegrations$2\1\217\0 = false;
                                        v0 = this.$context$inlined.getApplicationContext();
                                        Intrinsics.checkNotNullExpressionValue((Object)v0, (String)"getApplicationContext(...)");
                                        $continuation.L$0 = SpillingKt.nullOutSpilledVariable((Object)value);
                                        $continuation.L$1 = SpillingKt.nullOutSpilledVariable((Object)$completion);
                                        $continuation.L$2 = SpillingKt.nullOutSpilledVariable((Object)value);
                                        $continuation.L$3 = SpillingKt.nullOutSpilledVariable((Object)$this$map_u24lambda_u240);
                                        $continuation.label = 1;
                                        v1 = var10_14.emit((Object)OtherSettingsPreferences.INSTANCE.playbackIntegrations(v0), (Continuation)$continuation);
                                        if (v1 == var5_5) {
                                            return var5_5;
                                        }
                                        ** GOTO lbl39
                                    }
                                    case 1: {
                                        $i$a$-unsafeTransform-FlowKt__TransformKt$map$1\1\47\0 = false;
                                        $this$map_u24lambda_u240 = (FlowCollector)$continuation.L$3;
                                        value = $continuation.L$2;
                                        $completion = $continuation.L$1;
                                        value = $continuation.L$0;
                                        ResultKt.throwOnFailure((Object)$result);
                                        v1 = $result;
lbl39:
                                        // 2 sources

                                        return Unit.INSTANCE;
                                    }
                                }
                                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
                            }
                        }, (Continuation)$continuation);
                        if (v0 == var5_5) {
                            return var5_5;
                        }
                        ** GOTO lbl30
                    }
                    case 1: {
                        $i$a$-unsafeFlow-FlowKt__EmittersKt$unsafeTransform$1\1\107\0 = false;
                        $this$unsafeTransform_u24lambda_u240 = (FlowCollector)$continuation.L$2;
                        $completion = $continuation.L$1;
                        collector = (FlowCollector)$continuation.L$0;
                        ResultKt.throwOnFailure((Object)$result);
                        v0 = $result;
lbl30:
                        // 2 sources

                        return Unit.INSTANCE;
                    }
                }
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
        })), (CoroutineContext)((CoroutineContext)AppDispatchers.INSTANCE.getIo()));
    }

    public final void setKeepLyricsAwake(@NotNull Context context, boolean enabled) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putBoolean(KEY_KEEP_LYRICS_AWAKE, enabled).apply();
    }

    public final void setStreamingAddonEnabled(@NotNull Context context, boolean enabled) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putBoolean(KEY_ADDON_ENABLED, enabled && !StringsKt.isBlank((CharSequence)this.playbackIntegrations(context).getAddonUrl())).apply();
    }

    public final void saveStreamingAddon(@NotNull Context context, @NotNull String url, @NotNull String name) {
        SharedPreferences.Editor editor;
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        Intrinsics.checkNotNullParameter((Object)url, (String)"url");
        Intrinsics.checkNotNullParameter((Object)name, (String)"name");
        SharedPreferences.Editor editor2 = editor = this.getPrefs(context).edit().putString(KEY_ADDON_URL, url).putString(KEY_ADDON_NAME, name);
        boolean bl = false;
        if (StringsKt.isBlank((CharSequence)url)) {
            editor2.putBoolean(KEY_ADDON_ENABLED, false);
        }
        editor.apply();
    }

    @NotNull
    public final PlayerExperienceSettings playerExperienceSettings(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        SharedPreferences sharedPreferences = this.getPrefs(context);
        boolean bl = false;
        return new PlayerExperienceSettings(sharedPreferences.getBoolean(KEY_ALBUM_GLOW, true), sharedPreferences.getBoolean(KEY_HIGH_CONTRAST, false), sharedPreferences.getBoolean(KEY_CROSSFADE, false), sharedPreferences.getBoolean(KEY_SPATIAL_AUDIO, false), sharedPreferences.getBoolean(KEY_SKIP_SILENCE, false), OutputPrecision.Companion.fromStored(sharedPreferences.getString(KEY_OUTPUT_PRECISION, null)));
    }

    @NotNull
    public final Flow<PlayerExperienceSettings> observePlayerExperience(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        Flow flow = FlowKt.conflate((Flow)FlowKt.callbackFlow((Function2)((Function2)new Function2<ProducerScope<? super Unit>, Continuation<? super Unit>, Object>(context, null){
            Object L$1;
            Object L$2;
            int label;
            private /* synthetic */ Object L$0;
            final /* synthetic */ Context $context;
            {
                this.$context = $context;
                super(2, $completion);
            }

            /*
             * WARNING - void declaration
             * Enabled force condition propagation
             * Lifted jumps to return sites
             */
            public final Object invokeSuspend(Object $result) {
                ProducerScope producerScope = (ProducerScope)this.L$0;
                Object object = IntrinsicsKt.getCOROUTINE_SUSPENDED();
                switch (this.label) {
                    case 0: {
                        void $this$callbackFlow;
                        ResultKt.throwOnFailure((Object)$result);
                        Context context = this.$context.getApplicationContext();
                        Intrinsics.checkNotNullExpressionValue((Object)context, (String)"getApplicationContext(...)");
                        SharedPreferences prefs = OtherSettingsPreferences.access$getPrefs(OtherSettingsPreferences.INSTANCE, context);
                        SharedPreferences.OnSharedPreferenceChangeListener listener = (arg_0, arg_1) -> observePlayerExperience.1.invokeSuspend$lambda$0((ProducerScope)$this$callbackFlow, arg_0, arg_1);
                        prefs.registerOnSharedPreferenceChangeListener(listener);
                        $this$callbackFlow.trySend-JP2dKIU((Object)Unit.INSTANCE);
                        this.L$0 = SpillingKt.nullOutSpilledVariable((Object)$this$callbackFlow);
                        this.L$1 = SpillingKt.nullOutSpilledVariable((Object)prefs);
                        this.L$2 = SpillingKt.nullOutSpilledVariable((Object)listener);
                        this.label = 1;
                        Object object2 = ProduceKt.awaitClose((ProducerScope)$this$callbackFlow, () -> observePlayerExperience.1.invokeSuspend$lambda$1(prefs, listener), (Continuation)((Continuation)this));
                        if (object2 != object) return Unit.INSTANCE;
                        return object;
                    }
                    case 1: {
                        SharedPreferences.OnSharedPreferenceChangeListener listener = (SharedPreferences.OnSharedPreferenceChangeListener)this.L$2;
                        SharedPreferences prefs = (SharedPreferences)this.L$1;
                        ResultKt.throwOnFailure((Object)$result);
                        Object object2 = $result;
                        return Unit.INSTANCE;
                    }
                }
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }

            public final Continuation<Unit> create(Object value, Continuation<?> $completion) {
                var var3_3 = new /* invalid duplicate definition of identical inner class */;
                var3_3.L$0 = value;
                return (Continuation)var3_3;
            }

            public final Object invoke(ProducerScope<? super Unit> p1, Continuation<? super Unit> p2) {
                return (this.create(p1, p2)).invokeSuspend(Unit.INSTANCE);
            }

            /*
             * Unable to fully structure code
             */
            private static final void invokeSuspend$lambda$0(ProducerScope $$this$callbackFlow, SharedPreferences var1_1, String key) {
                if (key == null) ** GOTO lbl25
                switch (key.hashCode()) {
                    case 1412900279: {
                        if (!key.equals("playback_spatial_audio")) {
                            break;
                        }
                        ** GOTO lbl25
                    }
                    case 1691136013: {
                        if (!key.equals("player_high_contrast_typography")) {
                            break;
                        }
                        ** GOTO lbl25
                    }
                    case -235544476: {
                        if (!key.equals("playback_output_precision")) {
                            break;
                        }
                        ** GOTO lbl25
                    }
                    case -1399286886: {
                        if (!key.equals("playback_crossfade_enabled")) {
                            break;
                        }
                        ** GOTO lbl25
                    }
                    case 835574651: {
                        if (!key.equals("player_dynamic_album_glow")) {
                            break;
                        }
                        ** GOTO lbl25
                    }
                    case -554948219: {
                        if (!key.equals("playback_skip_silence")) break;
lbl25:
                        // 7 sources

                        $$this$callbackFlow.trySend-JP2dKIU((Object)Unit.INSTANCE);
                    }
                }
            }

            private static final Unit invokeSuspend$lambda$1(SharedPreferences $prefs, SharedPreferences.OnSharedPreferenceChangeListener $listener) {
                $prefs.unregisterOnSharedPreferenceChangeListener($listener);
                return Unit.INSTANCE;
            }
        })));
        boolean bl = false;
        Flow flow2 = flow;
        boolean bl2 = false;
        boolean bl3 = false;
        return FlowKt.flowOn((Flow)FlowKt.distinctUntilChanged((Flow)((Flow)new Flow<PlayerExperienceSettings>(flow2, context){
            final /* synthetic */ Flow $this_unsafeTransform$inlined;
            final /* synthetic */ Context $context$inlined;
            {
                this.$this_unsafeTransform$inlined = flow;
                this.$context$inlined = context;
            }

            /*
             * Unable to fully structure code
             */
            public Object collect(FlowCollector collector, Continuation $completion) {
                if (!($completion instanceof observePlayerExperience$$inlined$map$1$1)) ** GOTO lbl-1000
                var3_3 = $completion;
                if ((var3_3.label & -2147483648) != 0) {
                    var3_3.label -= -2147483648;
                } else lbl-1000:
                // 2 sources

                {
                    $continuation = new ContinuationImpl(this, $completion){
                        /* synthetic */ Object result;
                        int label;
                        final /* synthetic */ observePlayerExperience$$inlined$map$1 this$0;
                        Object L$0;
                        Object L$1;
                        Object L$2;
                        {
                            this.this$0 = this$0;
                            super($completion);
                        }

                        public final Object invokeSuspend(Object $result) {
                            this.result = $result;
                            this.label |= Integer.MIN_VALUE;
                            return this.this$0.collect(null, (Continuation)this);
                        }
                    };
                }
                $result = $continuation.result;
                var5_5 = IntrinsicsKt.getCOROUTINE_SUSPENDED();
                switch ($continuation.label) {
                    case 0: {
                        ResultKt.throwOnFailure((Object)$result);
                        var6_6 = $continuation;
                        $this$unsafeTransform_u24lambda_u240 = collector;
                        $i$a$-unsafeFlow-FlowKt__EmittersKt$unsafeTransform$1\1\107\0 = false;
                        $continuation.L$0 = SpillingKt.nullOutSpilledVariable((Object)collector);
                        $continuation.L$1 = SpillingKt.nullOutSpilledVariable((Object)$completion);
                        $continuation.L$2 = SpillingKt.nullOutSpilledVariable((Object)$this$unsafeTransform_u24lambda_u240);
                        $continuation.label = 1;
                        v0 = this.$this_unsafeTransform$inlined.collect(new FlowCollector($this$unsafeTransform_u24lambda_u240, this.$context$inlined){
                            final /* synthetic */ FlowCollector $this_unsafeFlow;
                            final /* synthetic */ Context $context$inlined;
                            {
                                this.$this_unsafeFlow = $receiver;
                                this.$context$inlined = context;
                            }

                            /*
                             * Unable to fully structure code
                             */
                            public final Object emit(Object value, Continuation $completion) {
                                if (!($completion instanceof observePlayerExperience$$inlined$map$1$2$1)) ** GOTO lbl-1000
                                var3_3 = $completion;
                                if ((var3_3.label & -2147483648) != 0) {
                                    var3_3.label -= -2147483648;
                                } else lbl-1000:
                                // 2 sources

                                {
                                    $continuation = new ContinuationImpl(this, $completion){
                                        Object L$0;
                                        /* synthetic */ Object result;
                                        int label;
                                        Object L$1;
                                        Object L$2;
                                        Object L$3;
                                        Object L$4;
                                        int I$0;
                                        final /* synthetic */ observePlayerExperience$$inlined$map$1$2 this$0;
                                        {
                                            this.this$0 = this$0;
                                            super($completion);
                                        }

                                        public final Object invokeSuspend(Object $result) {
                                            this.result = $result;
                                            this.label |= Integer.MIN_VALUE;
                                            return this.this$0.emit(null, (Continuation)this);
                                        }
                                    };
                                }
                                $result = $continuation.result;
                                var5_5 = IntrinsicsKt.getCOROUTINE_SUSPENDED();
                                switch ($continuation.label) {
                                    case 0: {
                                        ResultKt.throwOnFailure((Object)$result);
                                        var6_6 = $continuation;
                                        var7_8 = value;
                                        $this$map_u24lambda_u240 = this.$this_unsafeFlow;
                                        $i$a$-unsafeTransform-FlowKt__TransformKt$map$1\1\47\0 = false;
                                        var10_14 = $this$map_u24lambda_u240;
                                        var11_15 = (Continuation)$continuation;
                                        it\1 = (Unit)value;
                                        $i$a$-map-OtherSettingsPreferences$observePlayerExperience$2\1\217\0 = false;
                                        v0 = this.$context$inlined.getApplicationContext();
                                        Intrinsics.checkNotNullExpressionValue((Object)v0, (String)"getApplicationContext(...)");
                                        $continuation.L$0 = SpillingKt.nullOutSpilledVariable((Object)value);
                                        $continuation.L$1 = SpillingKt.nullOutSpilledVariable((Object)$completion);
                                        $continuation.L$2 = SpillingKt.nullOutSpilledVariable((Object)value);
                                        $continuation.L$3 = SpillingKt.nullOutSpilledVariable((Object)$this$map_u24lambda_u240);
                                        $continuation.label = 1;
                                        v1 = var10_14.emit((Object)OtherSettingsPreferences.INSTANCE.playerExperienceSettings(v0), (Continuation)$continuation);
                                        if (v1 == var5_5) {
                                            return var5_5;
                                        }
                                        ** GOTO lbl39
                                    }
                                    case 1: {
                                        $i$a$-unsafeTransform-FlowKt__TransformKt$map$1\1\47\0 = false;
                                        $this$map_u24lambda_u240 = (FlowCollector)$continuation.L$3;
                                        value = $continuation.L$2;
                                        $completion = $continuation.L$1;
                                        value = $continuation.L$0;
                                        ResultKt.throwOnFailure((Object)$result);
                                        v1 = $result;
lbl39:
                                        // 2 sources

                                        return Unit.INSTANCE;
                                    }
                                }
                                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
                            }
                        }, (Continuation)$continuation);
                        if (v0 == var5_5) {
                            return var5_5;
                        }
                        ** GOTO lbl30
                    }
                    case 1: {
                        $i$a$-unsafeFlow-FlowKt__EmittersKt$unsafeTransform$1\1\107\0 = false;
                        $this$unsafeTransform_u24lambda_u240 = (FlowCollector)$continuation.L$2;
                        $completion = $continuation.L$1;
                        collector = (FlowCollector)$continuation.L$0;
                        ResultKt.throwOnFailure((Object)$result);
                        v0 = $result;
lbl30:
                        // 2 sources

                        return Unit.INSTANCE;
                    }
                }
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
        })), (CoroutineContext)((CoroutineContext)AppDispatchers.INSTANCE.getIo()));
    }

    public final void setDynamicAlbumGlow(@NotNull Context context, boolean enabled) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putBoolean(KEY_ALBUM_GLOW, enabled).apply();
    }

    public final void setHighContrastTypography(@NotNull Context context, boolean enabled) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putBoolean(KEY_HIGH_CONTRAST, enabled).apply();
    }

    public final void setCrossfade(@NotNull Context context, boolean enabled) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putBoolean(KEY_CROSSFADE, enabled).apply();
    }

    public final void setSpatialAudio(@NotNull Context context, boolean enabled) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putBoolean(KEY_SPATIAL_AUDIO, enabled).apply();
    }

    public final void setSkipSilence(@NotNull Context context, boolean enabled) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putBoolean(KEY_SKIP_SILENCE, enabled).apply();
    }

    public final void setOutputPrecision(@NotNull Context context, @NotNull OutputPrecision precision) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        Intrinsics.checkNotNullParameter((Object)((Object)precision), (String)"precision");
        this.getPrefs(context).edit().putString(KEY_OUTPUT_PRECISION, precision.name()).apply();
    }

    private final SharedPreferences getPrefs(Context context) {
        return ReactiveSettingsRepository.Companion.preferences(context, PREFS_NAME);
    }

    public final boolean isDynamicGlassEnabled(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        return this.getPrefs(context).getBoolean(KEY_DYNAMIC_GLASS_ENABLED, true);
    }

    public final void setDynamicGlassEnabled(@NotNull Context context, boolean enabled) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putBoolean(KEY_DYNAMIC_GLASS_ENABLED, enabled).apply();
    }

    public final boolean isProxyEnabled(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        return this.getPrefs(context).getBoolean(KEY_PROXY_ENABLED, false);
    }

    public final void setProxyEnabled(@NotNull Context context, boolean enabled) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply();
    }

    @NotNull
    public final String getProxyHost(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        String string = this.getPrefs(context).getString(KEY_PROXY_HOST, "127.0.0.1");
        if (string == null) {
            string = "127.0.0.1";
        }
        return string;
    }

    public final void setProxyHost(@NotNull Context context, @NotNull String host) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        Intrinsics.checkNotNullParameter((Object)host, (String)"host");
        this.getPrefs(context).edit().putString(KEY_PROXY_HOST, ((Object)StringsKt.trim((CharSequence)host)).toString()).apply();
    }

    public final int getProxyPort(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        return this.getPrefs(context).getInt(KEY_PROXY_PORT, 8080);
    }

    public final void setProxyPort(@NotNull Context context, int port) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putInt(KEY_PROXY_PORT, port).apply();
    }

    @NotNull
    public final String getProxyType(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        String string = this.getPrefs(context).getString(KEY_PROXY_TYPE, "HTTP");
        if (string == null) {
            string = "HTTP";
        }
        return string;
    }

    public final void setProxyType(@NotNull Context context, @NotNull String type) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        Intrinsics.checkNotNullParameter((Object)type, (String)"type");
        this.getPrefs(context).edit().putString(KEY_PROXY_TYPE, type).apply();
    }

    public final boolean isHapticsEnabled(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        return this.getPrefs(context).getBoolean(KEY_HAPTICS_ENABLED, true);
    }

    public final void setHapticsEnabled(@NotNull Context context, boolean enabled) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        this.getPrefs(context).edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply();
    }

    @NotNull
    public final List<ScaleOption> getTEXT_SIZE_OPTIONS() {
        return TEXT_SIZE_OPTIONS;
    }

    public final int getTextSizeIndex(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        int idx = this.getPrefs(context).getInt(KEY_TEXT_SIZE_INDEX, 3);
        return RangesKt.coerceIn((int)idx, (int)0, (int)CollectionsKt.getLastIndex(TEXT_SIZE_OPTIONS));
    }

    public final void setTextSizeIndex(@NotNull Context context, int index) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        int safeIndex = RangesKt.coerceIn((int)index, (int)0, (int)CollectionsKt.getLastIndex(TEXT_SIZE_OPTIONS));
        this.getPrefs(context).edit().putInt(KEY_TEXT_SIZE_INDEX, safeIndex).apply();
    }

    public final float getTextScale(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        int index = this.getTextSizeIndex(context);
        ScaleOption scaleOption = (ScaleOption)CollectionsKt.getOrNull(TEXT_SIZE_OPTIONS, (int)index);
        return scaleOption != null ? scaleOption.getScale() : 0.95f;
    }

    @NotNull
    public final List<ScaleOption> getUI_SIZE_OPTIONS() {
        return UI_SIZE_OPTIONS;
    }

    public final int getUiSizeIndex(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        int idx = this.getPrefs(context).getInt(KEY_UI_SIZE_INDEX, 3);
        return RangesKt.coerceIn((int)idx, (int)0, (int)CollectionsKt.getLastIndex(UI_SIZE_OPTIONS));
    }

    public final void setUiSizeIndex(@NotNull Context context, int index) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        int safeIndex = RangesKt.coerceIn((int)index, (int)0, (int)CollectionsKt.getLastIndex(UI_SIZE_OPTIONS));
        this.getPrefs(context).edit().putInt(KEY_UI_SIZE_INDEX, safeIndex).apply();
    }

    public final float getUiScale(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        int index = this.getUiSizeIndex(context);
        ScaleOption scaleOption = (ScaleOption)CollectionsKt.getOrNull(UI_SIZE_OPTIONS, (int)index);
        return scaleOption != null ? scaleOption.getScale() : 0.94f;
    }

    public final int getTotalPlayedCount(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        return this.getPrefs(context).getInt(KEY_TOTAL_PLAYED_COUNT, 24);
    }

    public final void incrementPlayedCount(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        int current = this.getTotalPlayedCount(context);
        this.getPrefs(context).edit().putInt(KEY_TOTAL_PLAYED_COUNT, current + 1).apply();
    }

    public final int getTotalListeningMinutes(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        return this.getPrefs(context).getInt(KEY_TOTAL_LISTENING_MINUTES, 86);
    }

    public final void addListeningMinutes(@NotNull Context context, int minutes) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        int current = this.getTotalListeningMinutes(context);
        this.getPrefs(context).edit().putInt(KEY_TOTAL_LISTENING_MINUTES, current + minutes).apply();
    }

    public final long calculateCacheSizeBytes(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        long size = 0L;
        try {
            size += this.getFolderSize(context.getCacheDir());
            File file = context.getExternalCacheDir();
            if (file != null) {
                File file2 = file;
                boolean bl = false;
                size += INSTANCE.getFolderSize(file2);
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return size;
    }

    private final long getFolderSize(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        long length = 0L;
        File[] fileArray = file.listFiles();
        if (fileArray == null) {
            return file.length();
        }
        for (File f : fileArray) {
            length += f.isDirectory() ? this.getFolderSize(f) : f.length();
        }
        return length;
    }

    @NotNull
    public final String formatBytes(long bytes) {
        Object object;
        double kb = (double)bytes / 1024.0;
        double mb = kb / 1024.0;
        double gb = mb / 1024.0;
        if (gb >= 1.0) {
            String string = "%.2f GB";
            Object[] objectArray = new Object[]{gb};
            String string2 = String.format(string, Arrays.copyOf(objectArray, objectArray.length));
            object = string2;
            Intrinsics.checkNotNullExpressionValue((Object)string2, (String)"format(...)");
        } else if (mb >= 1.0) {
            String string = "%.1f MB";
            Object[] objectArray = new Object[]{mb};
            String string3 = String.format(string, Arrays.copyOf(objectArray, objectArray.length));
            object = string3;
            Intrinsics.checkNotNullExpressionValue((Object)string3, (String)"format(...)");
        } else if (kb >= 1.0) {
            String string = "%.0f KB";
            Object[] objectArray = new Object[]{kb};
            String string4 = String.format(string, Arrays.copyOf(objectArray, objectArray.length));
            object = string4;
            Intrinsics.checkNotNullExpressionValue((Object)string4, (String)"format(...)");
        } else {
            object = bytes + " B";
        }
        return object;
    }

    public final boolean clearCache(@NotNull Context context) {
        Intrinsics.checkNotNullParameter((Object)context, (String)"context");
        boolean success = true;
        try {
            this.clearCacheDir(context.getCacheDir());
            File file = context.getExternalCacheDir();
            if (file != null) {
                File file2 = file;
                boolean bl = false;
                INSTANCE.clearCacheDir(file2);
            }
            this.getPrefs(context).edit().putLong(KEY_CACHE_CLEARED_TIMESTAMP, System.currentTimeMillis()).apply();
        }
        catch (Exception exception) {
            success = false;
        }
        return success;
    }

    private final void clearCacheDir(File dir) {
        block4: {
            if (dir == null || !dir.exists()) {
                return;
            }
            File[] fileArray = dir.listFiles();
            if (fileArray == null) break block4;
            File[] fileArray2 = fileArray;
            boolean bl = false;
            int n = fileArray2.length;
            for (int i = 0; i < n; ++i) {
                File file;
                File file2 = file = fileArray2[i];
                boolean bl2 = false;
                try {
                    boolean bl3 = file2.isDirectory() ? INSTANCE.deleteDir(file2) : file2.delete();
                    continue;
                }
                catch (Exception exception) {
                }
            }
        }
    }

    private final boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] stringArray = dir.list();
            if (stringArray == null) {
                return false;
            }
            for (String child : stringArray) {
                boolean success = this.deleteDir(new File(dir, child));
                if (success) continue;
                return false;
            }
            return dir.delete();
        }
        if (dir != null && dir.isFile()) {
            return dir.delete();
        }
        return false;
    }

    public static final /* synthetic */ SharedPreferences access$getPrefs(OtherSettingsPreferences $this, Context context) {
        return $this.getPrefs(context);
    }

    public static final /* synthetic */ void access$setLyricsListener$p(SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
        lyricsListener = onSharedPreferenceChangeListener;
    }

    public static final /* synthetic */ MutableStateFlow access$getLyricsState$p() {
        return lyricsState;
    }

    static {
        Object[] objectArray = new String[]{"Auto", "lrc.red", "BiniLyrics", "BetterLyrics", "PaxSenix", "LyricsPlus", "Unison", "BetterLyrics Portato", "PaxSenix: Spotify", "PaxSenix: Musixmatch", "Musixmatch", "KuGou", "LRCLIB", "Genius"};
        LYRICS_PROVIDERS = CollectionsKt.listOf((Object[])objectArray);
        lyricsScope = CoroutineScopeKt.CoroutineScope((CoroutineContext)SupervisorKt.SupervisorJob$default(null, (int)1, null).plus((CoroutineContext)CoroutineDispatcher.limitedParallelism$default((CoroutineDispatcher)Dispatchers.getIO(), (int)1, null, (int)2, null)));
        lyricsStarted = new AtomicBoolean(false);
        lyricsState = StateFlowKt.MutableStateFlow((Object)new LyricsSettings(null, 0, false, 7, null));
        objectArray = new ScaleOption[]{new ScaleOption(0, "Smallest", 0.72f, "-24%"), new ScaleOption(1, "Extra Small", 0.8f, "-16%"), new ScaleOption(2, "Small", 0.88f, "-8%"), new ScaleOption(3, "Default", 0.95f, "Standard"), new ScaleOption(4, "Large", 1.05f, "+10%"), new ScaleOption(5, "Extra Large", 1.15f, "+20%"), new ScaleOption(6, "Largest", 1.25f, "+30%")};
        TEXT_SIZE_OPTIONS = CollectionsKt.listOf((Object[])objectArray);
        objectArray = new ScaleOption[]{new ScaleOption(0, "Smallest", 0.76f, "-18%"), new ScaleOption(1, "Extra Small", 0.82f, "-12%"), new ScaleOption(2, "Small", 0.88f, "-6%"), new ScaleOption(3, "Default", 0.94f, "Standard"), new ScaleOption(4, "Large", 1.0f, "+6%"), new ScaleOption(5, "Extra Large", 1.06f, "+12%"), new ScaleOption(6, "Largest", 1.14f, "+20%")};
        UI_SIZE_OPTIONS = CollectionsKt.listOf((Object[])objectArray);
    }

    @Metadata(mv={2, 4, 0}, k=1, xi=48, d1={"\u0000,\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\u0007\n\u0002\b\u0010\n\u0002\u0010\u000b\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\b\u0087\b\u0018\u00002\u00020\u0001B'\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u0012\u0006\u0010\u0006\u001a\u00020\u0007\u0012\u0006\u0010\b\u001a\u00020\u0005\u00a2\u0006\u0004\b\t\u0010\nJ\t\u0010\u0012\u001a\u00020\u0003H\u00c6\u0003J\t\u0010\u0013\u001a\u00020\u0005H\u00c6\u0003J\t\u0010\u0014\u001a\u00020\u0007H\u00c6\u0003J\t\u0010\u0015\u001a\u00020\u0005H\u00c6\u0003J1\u0010\u0016\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\b\b\u0002\u0010\u0004\u001a\u00020\u00052\b\b\u0002\u0010\u0006\u001a\u00020\u00072\b\b\u0002\u0010\b\u001a\u00020\u0005H\u00c6\u0001J\u0014\u0010\u0017\u001a\u00020\u00182\b\u0010\u0019\u001a\u0004\u0018\u00010\u0001H\u00d6\u0083\u0004J\n\u0010\u001a\u001a\u00020\u0003H\u00d6\u0081\u0004J\n\u0010\u001b\u001a\u00020\u0005H\u00d6\u0081\u0004R\u0011\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u000b\u0010\fR\u0011\u0010\u0004\u001a\u00020\u0005\u00a2\u0006\b\n\u0000\u001a\u0004\b\r\u0010\u000eR\u0011\u0010\u0006\u001a\u00020\u0007\u00a2\u0006\b\n\u0000\u001a\u0004\b\u000f\u0010\u0010R\u0011\u0010\b\u001a\u00020\u0005\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0011\u0010\u000e\u00ca\u0001\f\b\u001d\u0012\b\b\u001e\u0012\u0004\b\u0003\u0010\u0002\u00a8\u0006\u001c"}, d2={"Lcom/example/data/local/OtherSettingsPreferences$ScaleOption;", "", "index", "", "label", "", "scale", "", "badge", "<init>", "(ILjava/lang/String;FLjava/lang/String;)V", "getIndex", "()I", "getLabel", "()Ljava/lang/String;", "getScale", "()F", "getBadge", "component1", "component2", "component3", "component4", "copy", "equals", "", "other", "hashCode", "toString", "app", "Landroidx/compose/runtime/internal/StabilityInferred;", "parameters"})
    @StabilityInferred(parameters=1)
    public static final class ScaleOption {
        private final int index;
        @NotNull
        private final String label;
        private final float scale;
        @NotNull
        private final String badge;
        @JvmField
        public static final int $stable;

        public ScaleOption(int index, @NotNull String label, float scale, @NotNull String badge) {
            Intrinsics.checkNotNullParameter((Object)label, (String)"label");
            Intrinsics.checkNotNullParameter((Object)badge, (String)"badge");
            this.index = index;
            this.label = label;
            this.scale = scale;
            this.badge = badge;
        }

        public final int getIndex() {
            return this.index;
        }

        @NotNull
        public final String getLabel() {
            return this.label;
        }

        public final float getScale() {
            return this.scale;
        }

        @NotNull
        public final String getBadge() {
            return this.badge;
        }

        public final int component1() {
            return this.index;
        }

        @NotNull
        public final String component2() {
            return this.label;
        }

        public final float component3() {
            return this.scale;
        }

        @NotNull
        public final String component4() {
            return this.badge;
        }

        @NotNull
        public final ScaleOption copy(int index, @NotNull String label, float scale, @NotNull String badge) {
            Intrinsics.checkNotNullParameter((Object)label, (String)"label");
            Intrinsics.checkNotNullParameter((Object)badge, (String)"badge");
            return new ScaleOption(index, label, scale, badge);
        }

        public static /* synthetic */ ScaleOption copy$default(ScaleOption scaleOption, int n, String string, float f, String string2, int n2, Object object) {
            if ((n2 & 1) != 0) {
                n = scaleOption.index;
            }
            if ((n2 & 2) != 0) {
                string = scaleOption.label;
            }
            if ((n2 & 4) != 0) {
                f = scaleOption.scale;
            }
            if ((n2 & 8) != 0) {
                string2 = scaleOption.badge;
            }
            return scaleOption.copy(n, string, f, string2);
        }

        @NotNull
        public String toString() {
            return "ScaleOption(index=" + this.index + ", label=" + this.label + ", scale=" + this.scale + ", badge=" + this.badge + ")";
        }

        public int hashCode() {
            int result = Integer.hashCode(this.index);
            result = result * 31 + this.label.hashCode();
            result = result * 31 + Float.hashCode(this.scale);
            result = result * 31 + this.badge.hashCode();
            return result;
        }

        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ScaleOption)) {
                return false;
            }
            ScaleOption scaleOption = (ScaleOption)other;
            if (this.index != scaleOption.index) {
                return false;
            }
            if (!Intrinsics.areEqual((Object)this.label, (Object)scaleOption.label)) {
                return false;
            }
            if (Float.compare(this.scale, scaleOption.scale) != 0) {
                return false;
            }
            return Intrinsics.areEqual((Object)this.badge, (Object)scaleOption.badge);
        }
    }
}
