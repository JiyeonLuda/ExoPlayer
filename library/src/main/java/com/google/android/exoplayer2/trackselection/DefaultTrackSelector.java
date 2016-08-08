/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.trackselection;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.util.Util;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * A {@link MappingTrackSelector} that allows configuration of common parameters.
 */
public class DefaultTrackSelector extends MappingTrackSelector {

  /**
   * If a dimension (i.e. width or height) of a video is greater or equal to this fraction of the
   * corresponding viewport dimension, then the video is considered as filling the viewport (in that
   * dimension).
   */
  private static final float FRACTION_TO_CONSIDER_FULLSCREEN = 0.98f;
  private static final int[] NO_TRACKS = new int[0];
  private static final String TAG = "DefaultTrackSelector";

  private final TrackSelection.Factory adaptiveVideoTrackSelectionFactory;

  // Audio and text.
  private String preferredLanguage;

  //Video.
  private boolean allowMixedMimeAdaptiveness;
  private boolean allowNonSeamlessAdaptiveness;
  private int maxVideoWidth;
  private int maxVideoHeight;
  private boolean exceedVideoConstraintsIfNecessary;
  private boolean orientationMayChange;
  private int viewportWidth;
  private int viewportHeight;

  /**
   * Constructs an instance that does not support adaptive video.
   *
   * @param eventHandler A handler to use when delivering events to listeners. May be null if
   *     listeners will not be added.
   */
  public DefaultTrackSelector(Handler eventHandler) {
    this(eventHandler, null);
  }

  /**
   * Constructs an instance that uses a factory to create adaptive video track selections.
   *
   * @param eventHandler A handler to use when delivering events to listeners. May be null if
   *     listeners will not be added.
   * @param adaptiveVideoTrackSelectionFactory A factory for adaptive video {@link TrackSelection}s,
   *     or null if the selector should not support adaptive video.
   */
  public DefaultTrackSelector(Handler eventHandler,
      TrackSelection.Factory adaptiveVideoTrackSelectionFactory) {
    super(eventHandler);
    this.adaptiveVideoTrackSelectionFactory = adaptiveVideoTrackSelectionFactory;
    allowNonSeamlessAdaptiveness = true;
    exceedVideoConstraintsIfNecessary = true;
    maxVideoWidth = Integer.MAX_VALUE;
    maxVideoHeight = Integer.MAX_VALUE;
    viewportWidth = Integer.MAX_VALUE;
    viewportHeight = Integer.MAX_VALUE;
    orientationMayChange = true;
  }

  /**
   * Sets the preferred language for audio and text tracks.
   *
   * @param preferredLanguage The language as defined by RFC 5646.
   */
  public void setPreferredLanguage(String preferredLanguage) {
    String adjustedPreferredLanguage = new Locale(preferredLanguage).getLanguage();
    if (!Util.areEqual(this.preferredLanguage, adjustedPreferredLanguage)) {
      this.preferredLanguage = adjustedPreferredLanguage;
      invalidate();
    }
  }

  /**
   * Sets whether to allow selections to contain mixed mime types.
   *
   * @param allowMixedMimeAdaptiveness Whether to allow selections to contain mixed mime types.
   */
  public void allowMixedMimeAdaptiveness(boolean allowMixedMimeAdaptiveness) {
    if (this.allowMixedMimeAdaptiveness != allowMixedMimeAdaptiveness) {
      this.allowMixedMimeAdaptiveness = allowMixedMimeAdaptiveness;
      invalidate();
    }
  }

  /**
   * Sets whether non-seamless adaptation is allowed.
   *
   * @param allowNonSeamlessAdaptiveness Whether non-seamless adaptation is allowed.
   */
  public void allowNonSeamlessAdaptiveness(boolean allowNonSeamlessAdaptiveness) {
    if (this.allowNonSeamlessAdaptiveness != allowNonSeamlessAdaptiveness) {
      this.allowNonSeamlessAdaptiveness = allowNonSeamlessAdaptiveness;
      invalidate();
    }
  }

  /**
   * Sets the maximum allowed size for video tracks.
   *
   * @param maxVideoWidth Maximum allowed width.
   * @param maxVideoHeight Maximum allowed height.
   */
  public void setMaxVideoSize(int maxVideoWidth, int maxVideoHeight) {
    if (this.maxVideoWidth != maxVideoWidth || this.maxVideoHeight != maxVideoHeight) {
      this.maxVideoWidth = maxVideoWidth;
      this.maxVideoHeight = maxVideoHeight;
      invalidate();
    }
  }

  /**
   * Equivalent to {@code setMaxVideoSize(1279, 719)}.
   */
  public void setMaxVideoSizeSd() {
    setMaxVideoSize(1279, 719);
  }

  /**
   * Equivalent to {@code setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)}.
   */
  public void clearMaxVideoSize() {
    setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
  }

  /**
   * Sets whether video constraints should be ignored when no selection can be made otherwise.
   *
   * @param exceedVideoConstraintsIfNecessary True to ignore video constraints when no selections
   *     can be made otherwise. False to force constraints anyway.
   */
  public void setExceedVideoConstraintsIfNecessary(boolean exceedVideoConstraintsIfNecessary) {
    if (this.exceedVideoConstraintsIfNecessary != exceedVideoConstraintsIfNecessary) {
      this.exceedVideoConstraintsIfNecessary = exceedVideoConstraintsIfNecessary;
      invalidate();
    }
  }

  /**
   * Sets the target viewport size for selecting video tracks.
   *
   * @param viewportWidth Viewport width in pixels.
   * @param viewportHeight Viewport height in pixels.
   * @param orientationMayChange Whether orientation may change during playback.
   */
  public void setViewportSize(int viewportWidth, int viewportHeight, boolean orientationMayChange) {
    if (this.viewportWidth != viewportWidth || this.viewportHeight != viewportHeight
        || this.orientationMayChange != orientationMayChange) {
      this.viewportWidth = viewportWidth;
      this.viewportHeight = viewportHeight;
      this.orientationMayChange = orientationMayChange;
      invalidate();
    }
  }

  /**
   * Retrieves the viewport size from the provided {@link Context} and calls
   * {@link #setViewportSize(int, int, boolean)} with this information.
   *
   * @param context The context to obtain the viewport size from.
   * @param orientationMayChange Whether orientation may change during playback.
   */
  public void setViewportSizeFromContext(Context context, boolean orientationMayChange) {
    Point viewportSize = getDisplaySize(context); // Assume the viewport is fullscreen.
    setViewportSize(viewportSize.x, viewportSize.y, orientationMayChange);
  }

  /**
   * Equivalent to {@code setViewportSize(Integer.MAX_VALUE, Integer.MAX_VALUE, true)}.
   */
  public void clearViewportConstraints() {
    setViewportSize(Integer.MAX_VALUE, Integer.MAX_VALUE, true);
  }

  // MappingTrackSelector implementation.

  @Override
  protected TrackSelection[] selectTracks(RendererCapabilities[] rendererCapabilities,
      TrackGroupArray[] rendererTrackGroupArrays, int[][][] rendererFormatSupports)
      throws ExoPlaybackException {
    // Make a track selection for each renderer.
    TrackSelection[] rendererTrackSelections = new TrackSelection[rendererCapabilities.length];
    for (int i = 0; i < rendererCapabilities.length; i++) {
      switch (rendererCapabilities[i].getTrackType()) {
        case C.TRACK_TYPE_VIDEO:
          rendererTrackSelections[i] = selectTrackForVideoRenderer(rendererCapabilities[i],
              rendererTrackGroupArrays[i], rendererFormatSupports[i], maxVideoWidth, maxVideoHeight,
              allowNonSeamlessAdaptiveness, allowMixedMimeAdaptiveness, viewportWidth,
              viewportHeight, orientationMayChange, adaptiveVideoTrackSelectionFactory,
              exceedVideoConstraintsIfNecessary);
          break;
        case C.TRACK_TYPE_AUDIO:
          rendererTrackSelections[i] = selectTrackForAudioRenderer(rendererTrackGroupArrays[i],
              rendererFormatSupports[i], preferredLanguage);
          break;
        case C.TRACK_TYPE_TEXT:
          rendererTrackSelections[i] = selectTrackForTextRenderer(rendererTrackGroupArrays[i],
              rendererFormatSupports[i], preferredLanguage);
          break;
        default:
          rendererTrackSelections[i] = selectFirstSupportedTrack(rendererTrackGroupArrays[i],
              rendererFormatSupports[i]);
          break;
      }
    }
    return rendererTrackSelections;
  }

  // Video track selection implementation.

  private static TrackSelection selectTrackForVideoRenderer(
      RendererCapabilities rendererCapabilities, TrackGroupArray groups, int[][] formatSupport,
      int maxVideoWidth, int maxVideoHeight, boolean allowNonSeamlessAdaptiveness,
      boolean allowMixedMimeAdaptiveness, int viewportWidth, int viewportHeight,
      boolean orientationMayChange, TrackSelection.Factory adaptiveVideoTrackSelectionFactory,
      boolean exceedConstraintsIfNecessary) throws ExoPlaybackException {
    TrackSelection selection = null;
    if (adaptiveVideoTrackSelectionFactory != null) {
      selection = selectAdaptiveVideoTrack(rendererCapabilities, groups, formatSupport,
          maxVideoWidth, maxVideoHeight, allowNonSeamlessAdaptiveness,
          allowMixedMimeAdaptiveness, viewportWidth, viewportHeight,
          orientationMayChange, adaptiveVideoTrackSelectionFactory);
    }
    if (selection == null) {
      selection = selectFixedVideoTrack(groups, formatSupport, maxVideoWidth, maxVideoHeight,
          viewportWidth, viewportHeight, orientationMayChange, exceedConstraintsIfNecessary);
    }
    return selection;
  }

  private static TrackSelection selectAdaptiveVideoTrack(RendererCapabilities rendererCapabilities,
      TrackGroupArray groups, int[][] formatSupport, int maxVideoWidth, int maxVideoHeight,
      boolean allowNonSeamlessAdaptiveness, boolean allowMixedMimeAdaptiveness, int viewportWidth,
      int viewportHeight, boolean orientationMayChange,
      TrackSelection.Factory adaptiveVideoTrackSelectionFactory) throws ExoPlaybackException {
    int requiredAdaptiveSupport = allowNonSeamlessAdaptiveness
        ? (RendererCapabilities.ADAPTIVE_NOT_SEAMLESS | RendererCapabilities.ADAPTIVE_SEAMLESS)
        : RendererCapabilities.ADAPTIVE_SEAMLESS;
    boolean allowMixedMimeTypes = allowMixedMimeAdaptiveness
        && (rendererCapabilities.supportsMixedMimeTypeAdaptation() & requiredAdaptiveSupport) != 0;
    TrackGroup largestAdaptiveGroup = null;
    int[] largestAdaptiveGroupTracks = NO_TRACKS;
    for (int i = 0; i < groups.length; i++) {
      TrackGroup group = groups.get(i);
      int[] adaptiveTracks = getAdaptiveTracksForGroup(group, formatSupport[i],
          allowMixedMimeTypes, requiredAdaptiveSupport, maxVideoWidth, maxVideoHeight,
          viewportWidth, viewportHeight, orientationMayChange);
      if (adaptiveTracks.length > largestAdaptiveGroupTracks.length) {
        largestAdaptiveGroup = group;
        largestAdaptiveGroupTracks = adaptiveTracks;
      }
    }
    return largestAdaptiveGroup == null ? null : adaptiveVideoTrackSelectionFactory
        .createTrackSelection(largestAdaptiveGroup, largestAdaptiveGroupTracks);
  }

  private static int[] getAdaptiveTracksForGroup(TrackGroup group, int[] formatSupport,
      boolean allowMixedMimeTypes, int requiredAdaptiveSupport, int maxVideoWidth,
      int maxVideoHeight, int viewportWidth, int viewportHeight, boolean orientationMayChange) {
    if (group.length < 2) {
      return NO_TRACKS;
    }

    List<Integer> selectedTrackIndices = getViewportFilteredTrackIndices(group, viewportWidth,
        viewportHeight, orientationMayChange);
    if (selectedTrackIndices.size() < 2) {
      return NO_TRACKS;
    }

    String selectedMimeType = null;
    if (!allowMixedMimeTypes) {
      // Select the mime type for which we have the most adaptive tracks.
      HashSet<String> seenMimeTypes = new HashSet<>();
      int selectedMimeTypeTrackCount = 0;
      for (int i = 0; i < selectedTrackIndices.size(); i++) {
        int trackIndex = selectedTrackIndices.get(i);
        String sampleMimeType = group.getFormat(trackIndex).sampleMimeType;
        if (!seenMimeTypes.contains(sampleMimeType)) {
          seenMimeTypes.add(sampleMimeType);
          int countForMimeType = getAdaptiveTrackCountForMimeType(group, formatSupport,
              requiredAdaptiveSupport, sampleMimeType, maxVideoWidth, maxVideoHeight,
              selectedTrackIndices);
          if (countForMimeType > selectedMimeTypeTrackCount) {
            selectedMimeType = sampleMimeType;
            selectedMimeTypeTrackCount = countForMimeType;
          }
        }
      }
    }

    // Filter by the selected mime type.
    filterAdaptiveTrackCountForMimeType(group, formatSupport, requiredAdaptiveSupport,
        selectedMimeType, maxVideoWidth, maxVideoHeight, selectedTrackIndices);

    return selectedTrackIndices.size() < 2 ? NO_TRACKS : Util.toArray(selectedTrackIndices);
  }

  private static int getAdaptiveTrackCountForMimeType(TrackGroup group, int[] formatSupport,
      int requiredAdaptiveSupport, String mimeType, int maxVideoWidth, int maxVideoHeight,
      List<Integer> selectedTrackIndices) {
    int adaptiveTrackCount = 0;
    for (int i = 0; i < selectedTrackIndices.size(); i++) {
      int trackIndex = selectedTrackIndices.get(i);
      if (isSupportedAdaptiveVideoTrack(group.getFormat(trackIndex), mimeType,
          formatSupport[trackIndex], requiredAdaptiveSupport, maxVideoWidth, maxVideoHeight)) {
        adaptiveTrackCount++;
      }
    }
    return adaptiveTrackCount;
  }

  private static void filterAdaptiveTrackCountForMimeType(TrackGroup group, int[] formatSupport,
      int requiredAdaptiveSupport, String mimeType, int maxVideoWidth, int maxVideoHeight,
      List<Integer> selectedTrackIndices) {
    for (int i = selectedTrackIndices.size() - 1; i >= 0; i--) {
      int trackIndex = selectedTrackIndices.get(i);
      if (!isSupportedAdaptiveVideoTrack(group.getFormat(trackIndex), mimeType,
          formatSupport[trackIndex], requiredAdaptiveSupport, maxVideoWidth, maxVideoHeight)) {
        selectedTrackIndices.remove(i);
      }
    }
  }

  private static boolean isSupportedAdaptiveVideoTrack(Format format, String mimeType,
      int formatSupport, int requiredAdaptiveSupport, int maxVideoWidth, int maxVideoHeight) {
    return isSupported(formatSupport) && ((formatSupport & requiredAdaptiveSupport) != 0)
        && (mimeType == null || Util.areEqual(format.sampleMimeType, mimeType))
        && (format.width == Format.NO_VALUE || format.width <= maxVideoWidth)
        && (format.height == Format.NO_VALUE || format.height <= maxVideoHeight);
  }

  private static TrackSelection selectFixedVideoTrack(TrackGroupArray groups,
      int[][] formatSupport, int maxVideoWidth, int maxVideoHeight, int viewportWidth,
      int viewportHeight, boolean orientationMayChange, boolean exceedConstraintsIfNecessary) {
    TrackGroup selectedGroup = null;
    int selectedTrackIndex = -1;
    int selectedPixelCount = Format.NO_VALUE;
    boolean selectedIsWithinConstraints = false;
    for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
      TrackGroup group = groups.get(groupIndex);
      List<Integer> selectedTrackIndices = getViewportFilteredTrackIndices(group, viewportWidth,
          viewportHeight, orientationMayChange);
      int[] trackFormatSupport = formatSupport[groupIndex];
      for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
        if (isSupported(trackFormatSupport[trackIndex])) {
          Format format = group.getFormat(trackIndex);
          boolean isWithinConstraints = selectedTrackIndices.contains(trackIndex)
              && (format.width == Format.NO_VALUE || format.width <= maxVideoWidth)
              && (format.height == Format.NO_VALUE || format.height <= maxVideoHeight);
          int pixelCount = format.getPixelCount();
          boolean selectTrack;
          if (selectedIsWithinConstraints) {
            selectTrack = isWithinConstraints
                && comparePixelCounts(pixelCount, selectedPixelCount) > 0;
          } else {
            selectTrack = isWithinConstraints || (exceedConstraintsIfNecessary
                && (selectedGroup == null
                || comparePixelCounts(pixelCount, selectedPixelCount) < 0));
          }
          if (selectTrack) {
            selectedGroup = group;
            selectedTrackIndex = trackIndex;
            selectedPixelCount = pixelCount;
            selectedIsWithinConstraints = isWithinConstraints;
          }
        }
      }
    }
    return selectedGroup == null ? null
        : new FixedTrackSelection(selectedGroup, selectedTrackIndex);
  }

  /**
   * Compares two pixel counts for order. A known pixel count is considered greater than
   * {@link Format#NO_VALUE}.
   *
   * @param first The first pixel count.
   * @param second The second pixel count.
   * @return A negative integer if the first pixel count is less than the second. Zero if they are
   *     equal. A positive integer if the first pixel count is greater than the second.
   */
  private static int comparePixelCounts(int first, int second) {
    return first == Format.NO_VALUE ? (second == Format.NO_VALUE ? 0 : -1)
        : (second == Format.NO_VALUE ? 1 : (first - second));
  }


  // Audio track selection implementation.

  private static TrackSelection selectTrackForAudioRenderer(TrackGroupArray groups,
      int[][] formatSupport, String preferredLanguage) {
    if (preferredLanguage != null) {
      for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
        TrackGroup trackGroup = groups.get(groupIndex);
        int[] trackFormatSupport = formatSupport[groupIndex];
        for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
          if (isSupported(trackFormatSupport[trackIndex])
              && formatHasLanguage(trackGroup.getFormat(trackIndex), preferredLanguage)) {
            return new FixedTrackSelection(trackGroup, trackIndex);
          }
        }
      }
    }
    // No preferred language was selected or no audio track presented the preferred language.
    return selectFirstSupportedTrack(groups, formatSupport);
  }

  // Text track selection implementation.

  private static TrackSelection selectTrackForTextRenderer(TrackGroupArray groups,
      int[][] formatSupport, String preferredLanguage) {
    TrackGroup firstForcedGroup = null;
    int firstForcedTrack = -1;
    for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
      TrackGroup trackGroup = groups.get(groupIndex);
      int[] trackFormatSupport = formatSupport[groupIndex];
      for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
        if (isSupported(trackFormatSupport[trackIndex])
            && (trackGroup.getFormat(trackIndex).selectionFlags
                & Format.SELECTION_FLAG_FORCED) != 0) {
          if (firstForcedGroup == null) {
            firstForcedGroup = trackGroup;
            firstForcedTrack = trackIndex;
          }
          if (formatHasLanguage(trackGroup.getFormat(trackIndex), preferredLanguage)) {
            return new FixedTrackSelection(trackGroup, trackIndex);
          }
        }
      }
    }
    return firstForcedGroup == null ? null
        : new FixedTrackSelection(firstForcedGroup, firstForcedTrack);
  }

  // General track selection methods.

  private static TrackSelection selectFirstSupportedTrack(TrackGroupArray groups,
      int[][] formatSupport) {
    for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
      TrackGroup trackGroup = groups.get(groupIndex);
      int[] trackFormatSupport = formatSupport[groupIndex];
      for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
        if (isSupported(trackFormatSupport[trackIndex])) {
          return new FixedTrackSelection(trackGroup, trackIndex);
        }
      }
    }
    return null;
  }

  private static boolean isSupported(int formatSupport) {
    return (formatSupport & RendererCapabilities.FORMAT_SUPPORT_MASK)
        == RendererCapabilities.FORMAT_HANDLED;
  }

  private static boolean formatHasLanguage(Format format, String language) {
    return language != null && language.equals(new Locale(format.language).getLanguage());
  }

  // Viewport size util methods.

  private static List<Integer> getViewportFilteredTrackIndices(TrackGroup group, int viewportWidth,
      int viewportHeight, boolean orientationMayChange) {
    // Initially include all indices.
    ArrayList<Integer> selectedTrackIndices = new ArrayList<>(group.length);
    for (int i = 0; i < group.length; i++) {
      selectedTrackIndices.add(i);
    }

    if (viewportWidth == Integer.MAX_VALUE || viewportHeight == Integer.MAX_VALUE) {
      // Viewport dimensions not set. Return the full set of indices.
      return selectedTrackIndices;
    }

    int maxVideoPixelsToRetain = Integer.MAX_VALUE;
    for (int i = 0; i < group.length; i++) {
      Format format = group.getFormat(i);
      // Keep track of the number of pixels of the selected format whose resolution is the
      // smallest to exceed the maximum size at which it can be displayed within the viewport.
      // We'll discard formats of higher resolution.
      if (format.width > 0 && format.height > 0) {
        Point maxVideoSizeInViewport = getMaxVideoSizeInViewport(orientationMayChange,
            viewportWidth, viewportHeight, format.width, format.height);
        int videoPixels = format.width * format.height;
        if (format.width >= (int) (maxVideoSizeInViewport.x * FRACTION_TO_CONSIDER_FULLSCREEN)
            && format.height >= (int) (maxVideoSizeInViewport.y * FRACTION_TO_CONSIDER_FULLSCREEN)
            && videoPixels < maxVideoPixelsToRetain) {
          maxVideoPixelsToRetain = videoPixels;
        }
      }
    }

    // Filter out formats that exceed maxVideoPixelsToRetain. These formats have an unnecessarily
    // high resolution given the size at which the video will be displayed within the viewport. Also
    // filter out formats with unknown dimensions, since we have some whose dimensions are known.
    if (maxVideoPixelsToRetain != Integer.MAX_VALUE) {
      for (int i = selectedTrackIndices.size() - 1; i >= 0; i--) {
        Format format = group.getFormat(selectedTrackIndices.get(i));
        int pixelCount = format.getPixelCount();
        if (pixelCount == Format.NO_VALUE || pixelCount > maxVideoPixelsToRetain) {
          selectedTrackIndices.remove(i);
        }
      }
    }

    return selectedTrackIndices;
  }

  /**
   * Given viewport dimensions and video dimensions, computes the maximum size of the video as it
   * will be rendered to fit inside of the viewport.
   */
  private static Point getMaxVideoSizeInViewport(boolean orientationMayChange, int viewportWidth,
      int viewportHeight, int videoWidth, int videoHeight) {
    if (orientationMayChange && (videoWidth > videoHeight) != (viewportWidth > viewportHeight)) {
      // Rotation is allowed, and the video will be larger in the rotated viewport.
      int tempViewportWidth = viewportWidth;
      viewportWidth = viewportHeight;
      viewportHeight = tempViewportWidth;
    }

    if (videoWidth * viewportHeight >= videoHeight * viewportWidth) {
      // Horizontal letter-boxing along top and bottom.
      return new Point(viewportWidth, Util.ceilDivide(viewportWidth * videoHeight, videoWidth));
    } else {
      // Vertical letter-boxing along edges.
      return new Point(Util.ceilDivide(viewportHeight * videoWidth, videoHeight), viewportHeight);
    }
  }

  private static Point getDisplaySize(Context context) {
    // Before API 25 the platform Display object does not provide a working way to identify Android
    // TVs that can show 4k resolution in a SurfaceView, so check for supported devices here.
    if (Util.SDK_INT < 25) {
      if ("Sony".equals(Util.MANUFACTURER) && Util.MODEL.startsWith("BRAVIA")
          && context.getPackageManager().hasSystemFeature("com.sony.dtv.hardware.panel.qfhd")) {
        return new Point(3840, 2160);
      } else if ("NVIDIA".equals(Util.MANUFACTURER) && Util.MODEL != null
          && Util.MODEL.contains("SHIELD")) {
        // Attempt to read sys.display-size.
        String sysDisplaySize = null;
        try {
          Class<?> systemProperties = Class.forName("android.os.SystemProperties");
          Method getMethod = systemProperties.getMethod("get", String.class);
          sysDisplaySize = (String) getMethod.invoke(systemProperties, "sys.display-size");
        } catch (Exception e) {
          Log.e(TAG, "Failed to read sys.display-size", e);
        }
        // If we managed to read sys.display-size, attempt to parse it.
        if (!TextUtils.isEmpty(sysDisplaySize)) {
          try {
            String[] sysDisplaySizeParts = sysDisplaySize.trim().split("x");
            if (sysDisplaySizeParts.length == 2) {
              int width = Integer.parseInt(sysDisplaySizeParts[0]);
              int height = Integer.parseInt(sysDisplaySizeParts[1]);
              if (width > 0 && height > 0) {
                return new Point(width, height);
              }
            }
          } catch (NumberFormatException e) {
            // Do nothing.
          }
          Log.e(TAG, "Invalid sys.display-size: " + sysDisplaySize);
        }
      }
    }

    WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    Display display = windowManager.getDefaultDisplay();
      Point displaySize = new Point();
    if (Util.SDK_INT >= 23) {
      getDisplaySizeV23(display, displaySize);
    } else if (Util.SDK_INT >= 17) {
      getDisplaySizeV17(display, displaySize);
    } else if (Util.SDK_INT >= 16) {
      getDisplaySizeV16(display, displaySize);
    } else {
      getDisplaySizeV9(display, displaySize);
    }
    return displaySize;
  }

  @TargetApi(23)
  private static void getDisplaySizeV23(Display display, Point outSize) {
    Display.Mode mode = display.getMode();
    outSize.x = mode.getPhysicalWidth();
    outSize.y = mode.getPhysicalHeight();
  }

  @TargetApi(17)
  private static void getDisplaySizeV17(Display display, Point outSize) {
    display.getRealSize(outSize);
  }

  @TargetApi(16)
  private static void getDisplaySizeV16(Display display, Point outSize) {
    display.getSize(outSize);
  }

  @SuppressWarnings("deprecation")
  private static void getDisplaySizeV9(Display display, Point outSize) {
    outSize.x = display.getWidth();
    outSize.y = display.getHeight();
  }

}