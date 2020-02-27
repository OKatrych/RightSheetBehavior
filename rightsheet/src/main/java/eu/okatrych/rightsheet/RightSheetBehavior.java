/*
 * Copyright (C) 2015 The Android Open Source Project
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

package eu.okatrych.rightsheet;

import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams;
import androidx.core.math.MathUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.core.view.accessibility.AccessibilityViewCommand;
import androidx.customview.view.AbsSavedState;
import androidx.customview.widget.ViewDragHelper;

import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * An interaction behavior plugin for a child view of {@link CoordinatorLayout} to make it work as a
 * right sheet.
 *
 * <p>To send useful accessibility events, set a title on right sheets that are windows or are
 * window-like. For RightSheetDialog use {@link RightSheetDialog#setTitle(int)}, and for
 * BottomSheetDialogFragment use {@link ViewCompat#setAccessibilityPaneTitle(View, CharSequence)}.
 */
public class RightSheetBehavior<V extends View> extends CoordinatorLayout.Behavior<V> {

    /**
     * Callback for monitoring events about right sheets.
     */
    public abstract static class RightSheetCallback {

        /**
         * Called when the right sheet changes its state.
         *
         * @param rightSheet The right sheet view.
         * @param newState    The new state. This will be one of {@link #STATE_DRAGGING}, {@link
         *                    #STATE_SETTLING}, {@link #STATE_EXPANDED}, {@link #STATE_COLLAPSED}, {@link
         *                    #STATE_HIDDEN}, or {@link #STATE_HALF_EXPANDED}.
         */
        public abstract void onStateChanged(@NonNull View rightSheet, @State int newState);

        /**
         * Called when the right sheet is being dragged.
         *
         * @param rightSheet The right sheet view.
         * @param slideOffset The new offset of this right sheet within [-1,1] range. Offset increases
         *                    as this right sheet is moving left. From 0 to 1 the sheet is between collapsed and
         *                    expanded states and from -1 to 0 it is between hidden and collapsed states.
         */
        public abstract void onSlide(@NonNull View rightSheet, float slideOffset);
    }

    /**
     * The right sheet is dragging.
     */
    public static final int STATE_DRAGGING = 1;

    /**
     * The right sheet is settling.
     */
    public static final int STATE_SETTLING = 2;

    /**
     * The right sheet is expanded.
     */
    public static final int STATE_EXPANDED = 3;

    /**
     * The right sheet is collapsed.
     */
    public static final int STATE_COLLAPSED = 4;

    /**
     * The right sheet is hidden.
     */
    public static final int STATE_HIDDEN = 5;

    /**
     * The right sheet is half-expanded (used when mFitToContents is false).
     */
    public static final int STATE_HALF_EXPANDED = 6;

    @IntDef({
            STATE_EXPANDED,
            STATE_COLLAPSED,
            STATE_DRAGGING,
            STATE_SETTLING,
            STATE_HIDDEN,
            STATE_HALF_EXPANDED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
    }

    /**
     * Peek at the 16:9 ratio keyline of its parent.
     *
     * <p>This can be used as a parameter for {@link #setPeekWidth(int)}. {@link #getPeekWidth()}
     * will return this when the value is set.
     */
    public static final int PEEK_WIDTH_AUTO = -1;

    /**
     * This flag will preserve the peekWidth int value on configuration change.
     */
    public static final int SAVE_PEEK_WIDTH = 0x1;

    /**
     * This flag will preserve the fitToContents boolean value on configuration change.
     */
    public static final int SAVE_FIT_TO_CONTENTS = 1 << 1;

    /**
     * This flag will preserve the hideable boolean value on configuration change.
     */
    public static final int SAVE_HIDEABLE = 1 << 2;

    /**
     * This flag will preserve the skipCollapsed boolean value on configuration change.
     */
    public static final int SAVE_SKIP_COLLAPSED = 1 << 3;

    /**
     * This flag will preserve all aforementioned values on configuration change.
     */
    public static final int SAVE_ALL = -1;

    /**
     * This flag will not preserve the aforementioned values set at runtime if the view is destroyed
     * and recreated. The only value preserved will be the positional state, e.g. collapsed, hidden,
     * expanded, etc. This is the default behavior.
     */
    public static final int SAVE_NONE = 0;

    @IntDef(
            flag = true,
            value = {
                    SAVE_PEEK_WIDTH,
                    SAVE_FIT_TO_CONTENTS,
                    SAVE_HIDEABLE,
                    SAVE_SKIP_COLLAPSED,
                    SAVE_ALL,
                    SAVE_NONE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SaveFlags {
    }

    private static final String TAG = "RightSheetBehavior";

    @SaveFlags
    private int saveFlags = SAVE_NONE;

    private static final int SIGNIFICANT_VEL_THRESHOLD = 500;

    private static final float HIDE_THRESHOLD = 0.5f;

    private static final float HIDE_FRICTION = 0.1f;

    private static final int CORNER_ANIMATION_DURATION = 500;

    private boolean fitToContents = true;

    private boolean updateImportantForAccessibilityOnSiblings = false;

    private float maximumVelocity;

    /**
     * Peek width set by the user.
     */
    private int peekWidth;

    /**
     * Whether or not to use automatic peek width.
     */
    private boolean peekWidthAuto;

    /**
     * Minimum peek width permitted.
     */
    private int peekWidthMin;

    /**
     * True if Behavior has a non-null value for the @shapeAppearance attribute
     */
    private boolean shapeThemingEnabled;

    private MaterialShapeDrawable materialShapeDrawable;

    /**
     * Default Shape Appearance to be used in right sheet
     */
    private ShapeAppearanceModel shapeAppearanceModelDefault;

    private boolean isShapeExpanded;

    private SettleRunnable settleRunnable = null;

    @Nullable
    private ValueAnimator interpolatorAnimator;

    private static final int DEF_STYLE_RES = R.style.Widget_Design_RightSheet_Modal;

    private int expandedOffset;

    private int fitToContentsOffset;

    private int halfExpandedOffset;

    private float halfExpandedRatio = 0.5f;

    private int collapsedOffset;

    private float elevation = -1;

    private boolean hideable;

    private boolean skipCollapsed;

    private boolean draggable = true;

    @State
    private int state = STATE_COLLAPSED;

    @Nullable
    private ViewDragHelper viewDragHelper;

    private boolean ignoreEvents;

    private int lastNestedScrollDx;

    private boolean nestedScrolled;

    private int parentWidth;

    private int parentHeight;

    @Nullable
    private WeakReference<V> viewRef;

    @Nullable
    private WeakReference<View> nestedScrollingChildRef;

    @NonNull
    private final ArrayList<RightSheetCallback> callbacks = new ArrayList<>();

    @Nullable
    private VelocityTracker velocityTracker;

    private int activePointerId;

    private int initialX;

    private boolean touchingScrollingChild;

    @Nullable
    private Map<View, Integer> importantForAccessibilityMap;

    public RightSheetBehavior() {
    }

    @SuppressLint("PrivateResource")
    public RightSheetBehavior(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.BottomSheetBehavior_Layout);
        this.shapeThemingEnabled = a.hasValue(R.styleable.BottomSheetBehavior_Layout_shapeAppearance);
        boolean hasBackgroundTint = a.hasValue(R.styleable.BottomSheetBehavior_Layout_backgroundTint);
        if (hasBackgroundTint) {
            ColorStateList rightSheetColor =
                    CompatUtils.getColorStateList(
                            context, a, R.styleable.BottomSheetBehavior_Layout_backgroundTint);
            createMaterialShapeDrawable(context, attrs, hasBackgroundTint, rightSheetColor);
        } else {
            createMaterialShapeDrawable(context, attrs, hasBackgroundTint);
        }
        createShapeValueAnimator();

        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            this.elevation = a.getDimension(R.styleable.BottomSheetBehavior_Layout_android_elevation, -1);
        }

        TypedValue value = a.peekValue(R.styleable.BottomSheetBehavior_Layout_behavior_peekHeight);
        if (value != null && value.data == PEEK_WIDTH_AUTO) {
            setPeekWidth(value.data);
        } else {
            setPeekWidth(
                    a.getDimensionPixelSize(
                            R.styleable.BottomSheetBehavior_Layout_behavior_peekHeight, PEEK_WIDTH_AUTO));
        }
        setHideable(a.getBoolean(R.styleable.BottomSheetBehavior_Layout_behavior_hideable, false));
        setFitToContents(
                a.getBoolean(R.styleable.BottomSheetBehavior_Layout_behavior_fitToContents, true));
        setSkipCollapsed(
                a.getBoolean(R.styleable.BottomSheetBehavior_Layout_behavior_skipCollapsed, false));
        //setDraggable(a.getBoolean(R.styleable.BottomSheetBehavior_Layout_behavior_draggable, true));
        setSaveFlags(a.getInt(R.styleable.BottomSheetBehavior_Layout_behavior_saveFlags, SAVE_NONE));
        setHalfExpandedRatio(
                a.getFloat(R.styleable.BottomSheetBehavior_Layout_behavior_halfExpandedRatio, 0.5f));

        value = a.peekValue(R.styleable.BottomSheetBehavior_Layout_behavior_expandedOffset);
        if (value != null && value.type == TypedValue.TYPE_FIRST_INT) {
            setExpandedOffset(value.data);
        } else {
            setExpandedOffset(
                    a.getDimensionPixelOffset(
                            R.styleable.BottomSheetBehavior_Layout_behavior_expandedOffset, 0
                    )
            );
        }
        a.recycle();
        ViewConfiguration configuration = ViewConfiguration.get(context);
        maximumVelocity = configuration.getScaledMaximumFlingVelocity();
    }

    @NonNull
    @Override
    public Parcelable onSaveInstanceState(@NonNull CoordinatorLayout parent, @NonNull V child) {
        return new SavedState(super.onSaveInstanceState(parent, child), this);
    }

    @Override
    public void onRestoreInstanceState(
            @NonNull CoordinatorLayout parent, @NonNull V child, @NonNull Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(parent, child, ss.getSuperState());
        // Restore Optional State values designated by saveFlags
        restoreOptionalState(ss);
        // Intermediate states are restored as collapsed state
        if (ss.state == STATE_DRAGGING || ss.state == STATE_SETTLING) {
            this.state = STATE_COLLAPSED;
        } else {
            this.state = ss.state;
        }
    }

    @Override
    public void onAttachedToLayoutParams(@NonNull LayoutParams layoutParams) {
        super.onAttachedToLayoutParams(layoutParams);
        // These may already be null, but just be safe, explicitly assign them. This lets us know the
        // first time we layout with this behavior by checking (viewRef == null).
        viewRef = null;
        viewDragHelper = null;
    }

    @Override
    public void onDetachedFromLayoutParams() {
        super.onDetachedFromLayoutParams();
        // Release references so we don't run unnecessary codepaths while not attached to a view.
        viewRef = null;
        viewDragHelper = null;
    }

    @Override
    public boolean onLayoutChild(
            @NonNull CoordinatorLayout parent, @NonNull V child, int layoutDirection) {
        if (ViewCompat.getFitsSystemWindows(parent) && !ViewCompat.getFitsSystemWindows(child)) {
            child.setFitsSystemWindows(true);
        }

        if (viewRef == null) {
            // First layout with this behavior.
            peekWidthMin =
                    parent.getResources().getDimensionPixelSize(R.dimen.design_right_sheet_peek_height_min);
            viewRef = new WeakReference<>(child);
            // Only set MaterialShapeDrawable as background if shapeTheming is enabled, otherwise will
            // default to android:background declared in styles or layout.
            if (shapeThemingEnabled && materialShapeDrawable != null) {
                ViewCompat.setBackground(child, materialShapeDrawable);
            }
            // Set elevation on MaterialShapeDrawable
            if (materialShapeDrawable != null) {
                // Use elevation attr if set on right sheet; otherwise, use elevation of child view.
                materialShapeDrawable.setElevation(
                        elevation == -1 ? ViewCompat.getElevation(child) : elevation);
                // Update the material shape based on initial state.
                isShapeExpanded = state == STATE_EXPANDED;
                materialShapeDrawable.setInterpolation(isShapeExpanded ? 0f : 1f);
            }
            updateAccessibilityActions();
            if (ViewCompat.getImportantForAccessibility(child)
                    == ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                ViewCompat.setImportantForAccessibility(child, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES);
            }
        }
        if (viewDragHelper == null) {
            viewDragHelper = ViewDragHelper.create(parent, dragCallback);
        }

        int savedLeft = child.getLeft();
        // First let the parent lay it out
        parent.onLayoutChild(child, layoutDirection);
        // Offset the right sheet
        parentWidth = parent.getWidth();
        parentHeight = parent.getHeight();
        fitToContentsOffset = Math.max(0, parentWidth - child.getWidth());
        calculateHalfExpandedOffset();
        calculateCollapsedOffset();

        if (state == STATE_EXPANDED) {
            ViewCompat.offsetLeftAndRight(child, getExpandedOffset());
        } else if (state == STATE_HALF_EXPANDED) {
            ViewCompat.offsetLeftAndRight(child, halfExpandedOffset);
        } else if (hideable && state == STATE_HIDDEN) {
            ViewCompat.offsetLeftAndRight(child, parentWidth);
        } else if (state == STATE_COLLAPSED) {
            ViewCompat.offsetLeftAndRight(child, collapsedOffset);
        } else if (state == STATE_DRAGGING || state == STATE_SETTLING) {
            ViewCompat.offsetLeftAndRight(child, savedLeft - child.getLeft());
        }

        nestedScrollingChildRef = new WeakReference<>(findScrollingChild(child));
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(
            @NonNull CoordinatorLayout parent, @NonNull V child, @NonNull MotionEvent event) {
        if (!child.isShown() || !draggable) {
            ignoreEvents = true;
            return false;
        }
        int action = event.getActionMasked();
        // Record the velocity
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);
        switch (action) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchingScrollingChild = false;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                // Reset the ignore flag
                if (ignoreEvents) {
                    ignoreEvents = false;
                    return false;
                }
                break;
            case MotionEvent.ACTION_DOWN:
                initialX = (int) event.getX();
                int initialY = (int) event.getY();
                // Only intercept nested scrolling events here if the view not being moved by the
                // ViewDragHelper.
                if (state != STATE_SETTLING) {
                    View scroll = nestedScrollingChildRef != null ? nestedScrollingChildRef.get() : null;
                    if (scroll != null && parent.isPointInChildBounds(scroll, initialX, initialY)) {
                        activePointerId = event.getPointerId(event.getActionIndex());
                        touchingScrollingChild = true;
                    }
                }
                ignoreEvents =
                        activePointerId == MotionEvent.INVALID_POINTER_ID
                                && !parent.isPointInChildBounds(child, initialX, initialY);
                break;
            default: // fall out
        }
        if (!ignoreEvents
                && viewDragHelper != null
                && viewDragHelper.shouldInterceptTouchEvent(event)) {
            return true;
        }
        // We have to handle cases that the ViewDragHelper does not capture the right sheet because
        // it is not the top most view of its parent. This is not necessary when the touch event is
        // happening over the scrolling content as nested scrolling logic handles that case.
        View scroll = nestedScrollingChildRef != null ? nestedScrollingChildRef.get() : null;
        return action == MotionEvent.ACTION_MOVE
                && scroll != null
                && !ignoreEvents
                && state != STATE_DRAGGING
                && !parent.isPointInChildBounds(scroll, (int) event.getX(), (int) event.getY())
                && viewDragHelper != null
                && Math.abs(initialX - event.getX()) > viewDragHelper.getTouchSlop();
    }

    @Override
    public boolean onTouchEvent(
            @NonNull CoordinatorLayout parent, @NonNull V child, @NonNull MotionEvent event) {
        if (!child.isShown()) {
            return false;
        }
        int action = event.getActionMasked();
        if (state == STATE_DRAGGING && action == MotionEvent.ACTION_DOWN) {
            return true;
        }
        if (viewDragHelper != null) {
            viewDragHelper.processTouchEvent(event);
        }
        // Record the velocity
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);
        // The ViewDragHelper tries to capture only the top-most View. We have to explicitly tell it
        // to capture the right sheet in case it is not captured and the touch slop is passed.
        if (action == MotionEvent.ACTION_MOVE && !ignoreEvents) {
            if (Math.abs(initialX - event.getX()) > viewDragHelper.getTouchSlop()) {
                viewDragHelper.captureChildView(child, event.getPointerId(event.getActionIndex()));
            }
        }
        return !ignoreEvents;
    }

    @Override
    public boolean onStartNestedScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View directTargetChild,
            @NonNull View target,
            int axes,
            int type) {
        lastNestedScrollDx = 0;
        nestedScrolled = false;
        return (axes & ViewCompat.SCROLL_AXIS_HORIZONTAL) != 0;
    }

    @Override
    public void onNestedPreScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View target,
            int dx,
            int dy,
            @NonNull int[] consumed,
            int type
    ) {
        if (type == ViewCompat.TYPE_NON_TOUCH) {
            // Ignore fling here. The ViewDragHelper handles it.
            return;
        }
        View scrollingChild = nestedScrollingChildRef != null ? nestedScrollingChildRef.get() : null;
        if (target != scrollingChild) {
            return;
        }
        int currentLeft = child.getLeft();
        int newLeft = currentLeft - dx;
        if (dx > 0) { // Left
            if (newLeft < getExpandedOffset()) {
                consumed[1] = currentLeft - getExpandedOffset();
                ViewCompat.offsetLeftAndRight(child, -consumed[1]);
                setStateInternal(STATE_EXPANDED);
            } else {
                if (!draggable) {
                    // Prevent dragging
                    return;
                }

                consumed[1] = dx;
                ViewCompat.offsetLeftAndRight(child, -dx);
                setStateInternal(STATE_DRAGGING);
            }
        } else if (dx < 0) { // Right
            if (!target.canScrollHorizontally(-1)) {
                if (newLeft <= collapsedOffset || hideable) {
                    if (!draggable) {
                        // Prevent dragging
                        return;
                    }

                    consumed[1] = dx;
                    ViewCompat.offsetLeftAndRight(child, -dx);
                    setStateInternal(STATE_DRAGGING);
                } else {
                    consumed[1] = currentLeft - collapsedOffset;
                    ViewCompat.offsetLeftAndRight(child, -consumed[1]);
                    setStateInternal(STATE_COLLAPSED);
                }
            }
        }
        dispatchOnSlide(child.getLeft());
        lastNestedScrollDx = dx;
        nestedScrolled = true;
    }

    @Override
    public void onStopNestedScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View target,
            int type
    ) {
        if (child.getLeft() == getExpandedOffset()) {
            setStateInternal(STATE_EXPANDED);
            return;
        }
        if (nestedScrollingChildRef == null
                || target != nestedScrollingChildRef.get()
                || !nestedScrolled
        ) {
            return;
        }
        int left;
        int targetState;
        if (lastNestedScrollDx > 0) {
            if (fitToContents) {
                left = fitToContentsOffset;
                targetState = STATE_EXPANDED;
            } else {
                int currentLeft = child.getLeft();
                if (currentLeft > halfExpandedOffset) {
                    left = halfExpandedOffset;
                    targetState = STATE_HALF_EXPANDED;
                } else {
                    left = expandedOffset;
                    targetState = STATE_EXPANDED;
                }
            }
        } else if (hideable && shouldHide(child, getXVelocity())) {
            left = parentWidth;
            targetState = STATE_HIDDEN;
        } else if (lastNestedScrollDx == 0) {
            int currentLeft = child.getLeft();
            if (fitToContents) {
                if (Math.abs(currentLeft - fitToContentsOffset) < Math.abs(currentLeft - collapsedOffset)) {
                    left = fitToContentsOffset;
                    targetState = STATE_EXPANDED;
                } else {
                    left = collapsedOffset;
                    targetState = STATE_COLLAPSED;
                }
            } else {
                if (currentLeft < halfExpandedOffset) {
                    if (currentLeft < Math.abs(currentLeft - collapsedOffset)) {
                        left = expandedOffset;
                        targetState = STATE_EXPANDED;
                    } else {
                        left = halfExpandedOffset;
                        targetState = STATE_HALF_EXPANDED;
                    }
                } else {
                    if (Math.abs(currentLeft - halfExpandedOffset) < Math.abs(currentLeft - collapsedOffset)) {
                        left = halfExpandedOffset;
                        targetState = STATE_HALF_EXPANDED;
                    } else {
                        left = collapsedOffset;
                        targetState = STATE_COLLAPSED;
                    }
                }
            }
        } else {
            if (fitToContents) {
                left = collapsedOffset;
                targetState = STATE_COLLAPSED;
            } else {
                // Settle to nearest width.
                int currentLeft = child.getLeft();
                if (Math.abs(currentLeft - halfExpandedOffset) < Math.abs(currentLeft - collapsedOffset)) {
                    left = halfExpandedOffset;
                    targetState = STATE_HALF_EXPANDED;
                } else {
                    left = collapsedOffset;
                    targetState = STATE_COLLAPSED;
                }
            }
        }
        startSettlingAnimation(child, targetState, left, false);
        nestedScrolled = false;
    }

    @Override
    public void onNestedScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View target,
            int dxConsumed,
            int dyConsumed,
            int dxUnconsumed,
            int dyUnconsumed,
            int type,
            @NonNull int[] consumed) {
        // Overridden to prevent the default consumption of the entire scroll distance.
    }

    @Override
    public boolean onNestedPreFling(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View target,
            float velocityX,
            float velocityY) {
        if (nestedScrollingChildRef != null) {
            return target == nestedScrollingChildRef.get()
                    && (state != STATE_EXPANDED
                    || super.onNestedPreFling(coordinatorLayout, child, target, velocityX, velocityY));
        } else {
            return false;
        }
    }

    /**
     * @return whether the width of the expanded sheet is determined by the width of its contents,
     * or if it is expanded in two stages (half the width of the parent container, full width of
     * parent container).
     */
    public boolean isFitToContents() {
        return fitToContents;
    }

    /**
     * Sets whether the width of the expanded sheet is determined by the width of its contents, or
     * if it is expanded in two stages (half the width of the parent container, full width of parent
     * container). Default value is true.
     *
     * @param fitToContents whether or not to fit the expanded sheet to its contents.
     */
    public void setFitToContents(boolean fitToContents) {
        if (this.fitToContents == fitToContents) {
            return;
        }
        this.fitToContents = fitToContents;

        // If sheet is already laid out, recalculate the collapsed offset based on new setting.
        // Otherwise, let onLayoutChild handle this later.
        if (viewRef != null) {
            calculateCollapsedOffset();
        }
        // Fix incorrect expanded settings depending on whether or not we are fitting sheet to contents.
        setStateInternal((this.fitToContents && state == STATE_HALF_EXPANDED) ? STATE_EXPANDED : state);

        updateAccessibilityActions();
    }

    /**
     * Sets the width of the right sheet when it is collapsed.
     *
     * @param peekWidth The width of the collapsed right sheet in pixels, or {@link
     *                   #PEEK_WIDTH_AUTO} to configure the sheet to peek automatically at 16:9 ratio keyline.
     * @attr ref
     * R.styleable#BottomSheetBehavior_Layout_behavior_peekWidth
     */
    public void setPeekWidth(int peekWidth) {
        setPeekWidth(peekWidth, false);
    }

    /**
     * Sets the width of the right sheet when it is collapsed while optionally animating between the
     * old width and the new width.
     *
     * @param peekWidth The width of the collapsed right sheet in pixels, or {@link
     *                   #PEEK_WIDTH_AUTO} to configure the sheet to peek automatically at 16:9 ratio keyline.
     * @param animate    Whether to animate between the old width and the new width.
     * @attr ref
     * R.styleable#BottomSheetBehavior_Layout_behavior_peekWidth
     */
    public final void setPeekWidth(int peekWidth, boolean animate) {
        boolean layout = false;
        if (peekWidth == PEEK_WIDTH_AUTO) {
            if (!peekWidthAuto) {
                peekWidthAuto = true;
                layout = true;
            }
        } else if (peekWidthAuto || this.peekWidth != peekWidth) {
            peekWidthAuto = false;
            this.peekWidth = Math.max(0, peekWidth);
            layout = true;
        }
        // If sheet is already laid out, recalculate the collapsed offset based on new setting.
        // Otherwise, let onLayoutChild handle this later.
        if (layout && viewRef != null) {
            calculateCollapsedOffset();
            if (state == STATE_COLLAPSED) {
                V view = viewRef.get();
                if (view != null) {
                    if (animate) {
                        settleToStatePendingLayout(state);
                    } else {
                        view.requestLayout();
                    }
                }
            }
        }
    }

    /**
     * Gets the width of the right sheet when it is collapsed.
     *
     * @return The width of the collapsed right sheet in pixels, or {@link #PEEK_WIDTH_AUTO} if the
     * sheet is configured to peek automatically at 16:9 ratio keyline
     * @attr ref
     * R.styleable#BottomSheetBehavior_Layout_behavior_peekWidth
     */
    public int getPeekWidth() {
        return peekWidthAuto ? PEEK_WIDTH_AUTO : peekWidth;
    }

    /**
     * Determines the width of the RightSheet in the {@link #STATE_HALF_EXPANDED} state. The
     * material guidelines recommended a value of 0.5, which results in the sheet filling half of the
     * parent. The width of the RightSheet will be smaller as this ratio is decreased and taller as
     * it is increased. The default value is 0.5.
     *
     * @param ratio a float between 0 and 1, representing the {@link #STATE_HALF_EXPANDED} ratio.
     * @attr R.styleable#RigthSheetBehavior_Layout_behavior_halfExpandedRatio
     */
    public void setHalfExpandedRatio(@FloatRange(from = 0.0f, to = 1.0f) float ratio) {

        if ((ratio <= 0) || (ratio >= 1)) {
            throw new IllegalArgumentException("ratio must be a float value between 0 and 1");
        }
        this.halfExpandedRatio = ratio;
        // If sheet is already laid out, recalculate the half expanded offset based on new setting.
        // Otherwise, let onLayoutChild handle this later.
        if (viewRef != null) {
            calculateHalfExpandedOffset();
        }
    }

    /**
     * Gets the ratio for the width of the RightSheet in the {@link #STATE_HALF_EXPANDED} state.
     *
     * @attr R.styleable#BottomSheetBehavior_Layout_behavior_halfExpandedRatio
     */
    @FloatRange(from = 0.0f, to = 1.0f)
    public float getHalfExpandedRatio() {
        return halfExpandedRatio;
    }

    /**
     * Determines the top offset of the RightSheet in the {@link #STATE_EXPANDED} state when
     * fitsToContent is false. The default value is 0, which results in the sheet matching the
     * parent's top.
     *
     * @param offset an integer value greater than equal to 0, representing the {@link
     *               #STATE_EXPANDED} offset. Value must not exceed the offset in the half expanded state.
     * @attr R.styleable#BottomSheetBehavior_Layout_behavior_expandedOffset
     */
    public void setExpandedOffset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
        this.expandedOffset = offset;
    }

    /**
     * Returns the current expanded offset. If {@code fitToContents} is true, it will automatically
     * pick the offset depending on the width of the content.
     *
     * @attr R.styleable#BottomSheetBehavior_Layout_behavior_expandedOffset
     */
    public int getExpandedOffset() {
        return fitToContents ? fitToContentsOffset : expandedOffset;
    }

    /**
     * Sets whether this right sheet can hide when it is swiped down.
     *
     * @param hideable {@code true} to make this right sheet hideable.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_hideable
     */
    public void setHideable(boolean hideable) {
        if (this.hideable != hideable) {
            this.hideable = hideable;
            if (!hideable && state == STATE_HIDDEN) {
                // Lift up to collapsed state
                setState(STATE_COLLAPSED);
            }
            updateAccessibilityActions();
        }
    }

    /**
     * Gets whether this right sheet can hide when it is swiped down.
     *
     * @return {@code true} if this right sheet can hide.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_hideable
     */
    public boolean isHideable() {
        return hideable;
    }

    /**
     * Sets whether this right sheet should skip the collapsed state when it is being hidden after it
     * is expanded once. Setting this to true has no effect unless the sheet is hideable.
     *
     * @param skipCollapsed True if the right sheet should skip the collapsed state.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_skipCollapsed
     */
    public void setSkipCollapsed(boolean skipCollapsed) {
        this.skipCollapsed = skipCollapsed;
    }

    /**
     * Sets whether this right sheet should skip the collapsed state when it is being hidden after it
     * is expanded once.
     *
     * @return Whether the right sheet should skip the collapsed state.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_skipCollapsed
     */
    public boolean getSkipCollapsed() {
        return skipCollapsed;
    }

    /**
     * Sets whether this right sheet is can be collapsed/expanded by dragging. Note: When disabling
     * dragging, an app will require to implement a custom way to expand/collapse the right sheet
     *
     * @param draggable {@code false} to prevent dragging the sheet to collapse and expand
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_draggable
     */
    public void setDraggable(boolean draggable) {
        this.draggable = draggable;
    }

    public boolean isDraggable() {
        return draggable;
    }

    /**
     * Sets save flags to be preserved in right sheet on configuration change.
     *
     * @param flags bitwise int of {@link #SAVE_PEEK_WIDTH}, {@link #SAVE_FIT_TO_CONTENTS}, {@link
     *              #SAVE_HIDEABLE}, {@link #SAVE_SKIP_COLLAPSED}, {@link #SAVE_ALL} and {@link #SAVE_NONE}.
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_saveFlags
     * @see #getSaveFlags()
     */
    public void setSaveFlags(@SaveFlags int flags) {
        this.saveFlags = flags;
    }

    /**
     * Returns the save flags.
     *
     * @attr ref R.styleable#BottomSheetBehavior_Layout_behavior_saveFlags
     * @see #setSaveFlags(int)
     */
    @SaveFlags
    public int getSaveFlags() {
        return this.saveFlags;
    }

    /**
     * Sets a callback to be notified of right sheet events.
     *
     * @param callback The callback to notify when right sheet events occur.
     * @deprecated use {@link #addRightSheetCallback(RightSheetCallback)} and {@link
     * #removeRightSheetCallback(RightSheetCallback)} instead
     */
    @Deprecated
    public void setRightSheetCallback(RightSheetCallback callback) {
        Log.w(
                TAG,
                "RightSheetBehavior now supports multiple callbacks. `setRightSheetCallback()` removes"
                        + " all existing callbacks, including ones set internally by library authors, which"
                        + " may result in unintended behavior. This may change in the future. Please use"
                        + " `addRightSheetCallback()` and `removeRightSheetCallback()` instead to set your"
                        + " own callbacks.");
        callbacks.clear();
        if (callback != null) {
            callbacks.add(callback);
        }
    }

    /**
     * Adds a callback to be notified of right sheet events.
     *
     * @param callback The callback to notify when right sheet events occur.
     */
    public void addRightSheetCallback(@NonNull RightSheetCallback callback) {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback);
        }
    }

    /**
     * Removes a previously added callback.
     *
     * @param callback The callback to remove.
     */
    public void removeRightSheetCallback(@NonNull RightSheetCallback callback) {
        callbacks.remove(callback);
    }

    /**
     * Sets the state of the right sheet. The right sheet will transition to that state with
     * animation.
     *
     * @param state One of {@link #STATE_COLLAPSED}, {@link #STATE_EXPANDED}, {@link #STATE_HIDDEN},
     *              or {@link #STATE_HALF_EXPANDED}.
     */
    public void setState(@State int state) {
        if (state == this.state) {
            return;
        }
        if (viewRef == null) {
            // The view is not laid out yet; modify mState and let onLayoutChild handle it later
            if (state == STATE_COLLAPSED
                    || state == STATE_EXPANDED
                    || state == STATE_HALF_EXPANDED
                    || (hideable && state == STATE_HIDDEN)) {
                this.state = state;
            }
            return;
        }
        settleToStatePendingLayout(state);
    }

    private void settleToStatePendingLayout(@State int state) {
        final V child = viewRef.get();
        if (child == null) {
            return;
        }
        // Start the animation; wait until a pending layout if there is one.
        ViewParent parent = child.getParent();
        if (parent != null && parent.isLayoutRequested() && ViewCompat.isAttachedToWindow(child)) {
            final int finalState = state;
            child.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            settleToState(child, finalState);
                        }
                    });
        } else {
            settleToState(child, state);
        }
    }

    /**
     * Gets the current state of the right sheet.
     *
     * @return One of {@link #STATE_EXPANDED}, {@link #STATE_HALF_EXPANDED}, {@link #STATE_COLLAPSED},
     * {@link #STATE_DRAGGING}, {@link #STATE_SETTLING}, or {@link #STATE_HALF_EXPANDED}.
     */
    @State
    public int getState() {
        return state;
    }

    private void setStateInternal(@State int state) {
        if (this.state == state) {
            return;
        }
        this.state = state;

        if (viewRef == null) {
            return;
        }

        View rightSheet = viewRef.get();
        if (rightSheet == null) {
            return;
        }

        if (state == STATE_EXPANDED) {
            updateImportantForAccessibility(true);
        } else if (state == STATE_HALF_EXPANDED || state == STATE_HIDDEN || state == STATE_COLLAPSED) {
            updateImportantForAccessibility(false);
        }

        updateDrawableForTargetState(state);
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onStateChanged(rightSheet, state);
        }
        updateAccessibilityActions();
    }

    private void updateDrawableForTargetState(@State int state) {
        if (state == STATE_SETTLING) {
            // Special case: we want to know which state we're settling to, so wait for another call.
            return;
        }

        boolean expand = state == STATE_EXPANDED;
        if (isShapeExpanded != expand) {
            isShapeExpanded = expand;
            if (materialShapeDrawable != null && interpolatorAnimator != null) {
                if (interpolatorAnimator.isRunning()) {
                    interpolatorAnimator.reverse();
                } else {
                    float to = expand ? 0f : 1f;
                    float from = 1f - to;
                    interpolatorAnimator.setFloatValues(from, to);
                    interpolatorAnimator.start();
                }
            }
        }
    }

    private int calculatePeekWidth() {
        if (peekWidthAuto) {
            return Math.max(peekWidthMin, parentWidth - parentHeight * 9 / 16);
        }
        return peekWidth;
    }

    // TO-CHECK
    private void calculateCollapsedOffset() {
        int peek = calculatePeekWidth();

        if (fitToContents) {
            collapsedOffset = Math.max(parentWidth - peek, fitToContentsOffset);
        } else {
            collapsedOffset = parentWidth - peek;
        }
    }

    // TO-CHECK
    private void calculateHalfExpandedOffset() {
        this.halfExpandedOffset = (int) (parentWidth * (1 - halfExpandedRatio));
    }

    private void reset() {
        activePointerId = ViewDragHelper.INVALID_POINTER;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void restoreOptionalState(@NonNull SavedState ss) {
        if (this.saveFlags == SAVE_NONE) {
            return;
        }
        if (this.saveFlags == SAVE_ALL || (this.saveFlags & SAVE_PEEK_WIDTH) == SAVE_PEEK_WIDTH) {
            this.peekWidth = ss.peekWidth;
        }
        if (this.saveFlags == SAVE_ALL
                || (this.saveFlags & SAVE_FIT_TO_CONTENTS) == SAVE_FIT_TO_CONTENTS) {
            this.fitToContents = ss.fitToContents;
        }
        if (this.saveFlags == SAVE_ALL || (this.saveFlags & SAVE_HIDEABLE) == SAVE_HIDEABLE) {
            this.hideable = ss.hideable;
        }
        if (this.saveFlags == SAVE_ALL
                || (this.saveFlags & SAVE_SKIP_COLLAPSED) == SAVE_SKIP_COLLAPSED) {
            this.skipCollapsed = ss.skipCollapsed;
        }
    }

    private boolean shouldHide(@NonNull View child, float xvel) {
        if (skipCollapsed) {
            return true;
        }
        if (child.getLeft() < collapsedOffset) {
            // It should not hide, but collapse.
            return false;
        }
        int peek = calculatePeekWidth();
        final float newLeft = child.getLeft() + xvel * HIDE_FRICTION;
        return Math.abs(newLeft - collapsedOffset) / (float) peek > HIDE_THRESHOLD;
    }

    @Nullable
    @VisibleForTesting
    private View findScrollingChild(View view) {
        if (ViewCompat.isNestedScrollingEnabled(view)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View scrollingChild = findScrollingChild(group.getChildAt(i));
                if (scrollingChild != null) {
                    return scrollingChild;
                }
            }
        }
        return null;
    }

    private void createMaterialShapeDrawable(
            @NonNull Context context, AttributeSet attrs, boolean hasBackgroundTint) {
        this.createMaterialShapeDrawable(context, attrs, hasBackgroundTint, null);
    }

    private void createMaterialShapeDrawable(
            @NonNull Context context,
            AttributeSet attrs,
            boolean hasBackgroundTint,
            @Nullable ColorStateList rightSheetColor) {
        if (this.shapeThemingEnabled) {
            this.shapeAppearanceModelDefault =
                    ShapeAppearanceModel.builder(context, attrs, R.attr.bottomSheetStyle, DEF_STYLE_RES)
                            .build();

            this.materialShapeDrawable = new MaterialShapeDrawable(shapeAppearanceModelDefault);
            this.materialShapeDrawable.initializeElevationOverlay(context);

            if (hasBackgroundTint && rightSheetColor != null) {
                materialShapeDrawable.setFillColor(rightSheetColor);
            } else {
                // If the tint isn't set, use the theme default background color.
                TypedValue defaultColor = new TypedValue();
                context.getTheme().resolveAttribute(android.R.attr.colorBackground, defaultColor, true);
                materialShapeDrawable.setTint(defaultColor.data);
            }
        }
    }

    private void createShapeValueAnimator() {
        interpolatorAnimator = ValueAnimator.ofFloat(0f, 1f);
        interpolatorAnimator.setDuration(CORNER_ANIMATION_DURATION);
        interpolatorAnimator.addUpdateListener(
                new AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(@NonNull ValueAnimator animation) {
                        float value = (float) animation.getAnimatedValue();
                        if (materialShapeDrawable != null) {
                            materialShapeDrawable.setInterpolation(value);
                        }
                    }
                });
    }

    private float getXVelocity() {
        if (velocityTracker == null) {
            return 0;
        }
        velocityTracker.computeCurrentVelocity(1000, maximumVelocity);
        return velocityTracker.getXVelocity(activePointerId);
    }

    private void settleToState(@NonNull View child, int state) {
        int left;
        if (state == STATE_COLLAPSED) {
            left = collapsedOffset;
        } else if (state == STATE_HALF_EXPANDED) {
            left = halfExpandedOffset;
            if (fitToContents && left <= fitToContentsOffset) {
                // Skip to the expanded state if we would scroll past the width of the contents.
                state = STATE_EXPANDED;
                left = fitToContentsOffset;
            }
        } else if (state == STATE_EXPANDED) {
            left = getExpandedOffset();
        } else if (hideable && state == STATE_HIDDEN) {
            left = parentWidth;
        } else {
            throw new IllegalArgumentException("Illegal state argument: " + state);
        }
        startSettlingAnimation(child, state, left, false);
    }

    private void startSettlingAnimation(View child, int state, int left, boolean settleFromViewDragHelper) {
        boolean startedSettling =
                settleFromViewDragHelper
                        ? viewDragHelper.settleCapturedViewAt(left, child.getTop())
                        : viewDragHelper.smoothSlideViewTo(child, left, child.getTop());
        if (startedSettling) {
            setStateInternal(STATE_SETTLING);
            // STATE_SETTLING won't animate the material shape, so do that here with the target state.
            updateDrawableForTargetState(state);
            if (settleRunnable == null) {
                // If the singleton SettleRunnable instance has not been instantiated, create it.
                settleRunnable = new SettleRunnable(child, state);
            }
            // If the SettleRunnable has not been posted, post it with the correct state.
            if (settleRunnable.isPosted == false) {
                settleRunnable.targetState = state;
                ViewCompat.postOnAnimation(child, settleRunnable);
                settleRunnable.isPosted = true;
            } else {
                // Otherwise, if it has been posted, just update the target state.
                settleRunnable.targetState = state;
            }
        } else {
            setStateInternal(state);
        }
    }

    private final ViewDragHelper.Callback dragCallback =
            new ViewDragHelper.Callback() {

                @Override
                public boolean tryCaptureView(@NonNull View child, int pointerId) {
                    if (state == STATE_DRAGGING) {
                        return false;
                    }
                    if (touchingScrollingChild) {
                        return false;
                    }
                    if (state == STATE_EXPANDED && activePointerId == pointerId) {
                        View scroll = nestedScrollingChildRef != null ? nestedScrollingChildRef.get() : null;
                        if (scroll != null && scroll.canScrollHorizontally(-1)) {
                            // Let the content scroll up
                            return false;
                        }
                    }
                    return viewRef != null && viewRef.get() == child;
                }

                @Override
                public void onViewPositionChanged(
                        @NonNull View changedView,
                        int left,
                        int top,
                        int dx,
                        int dy
                ) {
                    dispatchOnSlide(left);
                }

                @Override
                public void onViewDragStateChanged(int state) {
                    if (state == ViewDragHelper.STATE_DRAGGING && draggable) {
                        setStateInternal(STATE_DRAGGING);
                    }
                }

                private boolean releasedLow(@NonNull View child) {
                    // Needs to be at least half way to the right.
                    return child.getLeft() > (parentWidth + getExpandedOffset()) / 2;
                }

                @Override
                public void onViewReleased(@NonNull View releasedChild, float xvel, float yvel) {
                    int left;
                    @State int targetState;
                    if (xvel < 0) { // Moving left
                        if (fitToContents) {
                            left = fitToContentsOffset;
                            targetState = STATE_EXPANDED;
                        } else {
                            int currentLeft = releasedChild.getLeft();
                            if (currentLeft > halfExpandedOffset) {
                                left = halfExpandedOffset;
                                targetState = STATE_HALF_EXPANDED;
                            } else {
                                left = expandedOffset;
                                targetState = STATE_EXPANDED;
                            }
                        }
                    } else if (hideable && shouldHide(releasedChild, xvel)) {
                        // Hide if the view was either released low or it was a significant horizontal swipe
                        // otherwise settle to closest expanded state.
                        if ((Math.abs(yvel) < Math.abs(xvel) && xvel > SIGNIFICANT_VEL_THRESHOLD)
                                || releasedLow(releasedChild)) {
                            left = parentWidth;
                            targetState = STATE_HIDDEN;
                        } else if (fitToContents) {
                            left = fitToContentsOffset;
                            targetState = STATE_EXPANDED;
                        } else if (Math.abs(releasedChild.getLeft() - expandedOffset)
                                < Math.abs(releasedChild.getLeft() - halfExpandedOffset)) {
                            left = expandedOffset;
                            targetState = STATE_EXPANDED;
                        } else {
                            left = halfExpandedOffset;
                            targetState = STATE_HALF_EXPANDED;
                        }
                    } else if (xvel == 0.f || Math.abs(yvel) > Math.abs(xvel)) {
                        // If the X velocity is 0 or the swipe was mostly vertical indicated by the Y velocity
                        // being greater than the X velocity, settle to the nearest correct width.
                        int currentLeft = releasedChild.getLeft();
                        if (fitToContents) {
                            if (Math.abs(currentLeft - fitToContentsOffset)
                                    < Math.abs(currentLeft - collapsedOffset)) {
                                left = fitToContentsOffset;
                                targetState = STATE_EXPANDED;
                            } else {
                                left = collapsedOffset;
                                targetState = STATE_COLLAPSED;
                            }
                        } else {
                            if (currentLeft < halfExpandedOffset) {
                                if (currentLeft < Math.abs(currentLeft - collapsedOffset)) {
                                    left = expandedOffset;
                                    targetState = STATE_EXPANDED;
                                } else {
                                    left = halfExpandedOffset;
                                    targetState = STATE_HALF_EXPANDED;
                                }
                            } else {
                                if (Math.abs(currentLeft - halfExpandedOffset)
                                        < Math.abs(currentLeft - collapsedOffset)) {
                                    left = halfExpandedOffset;
                                    targetState = STATE_HALF_EXPANDED;
                                } else {
                                    left = collapsedOffset;
                                    targetState = STATE_COLLAPSED;
                                }
                            }
                        }
                    } else { // Moving Right
                        if (fitToContents) {
                            left = collapsedOffset;
                            targetState = STATE_COLLAPSED;
                        } else {
                            // Settle to the nearest correct width.
                            int currentLeft = releasedChild.getLeft();
                            if (Math.abs(currentLeft - halfExpandedOffset)
                                    < Math.abs(currentLeft - collapsedOffset)) {
                                left = halfExpandedOffset;
                                targetState = STATE_HALF_EXPANDED;
                            } else {
                                left = collapsedOffset;
                                targetState = STATE_COLLAPSED;
                            }
                        }
                    }
                    startSettlingAnimation(releasedChild, targetState, left, true);
                }

                @Override
                public int clampViewPositionHorizontal(@NonNull View child, int left, int dx) {
                    return MathUtils.clamp(
                            left, getExpandedOffset(), hideable ? parentWidth : collapsedOffset
                    );
                }

                @Override
                public int getViewHorizontalDragRange(@NonNull View child) {
                    if (hideable) {
                        return parentWidth;
                    } else {
                        return collapsedOffset;
                    }
                }
            };

    private void dispatchOnSlide(int left) {
        View sheet = viewRef.get();
        if (sheet != null && !callbacks.isEmpty()) {
            float slideOffset =
                    (left > collapsedOffset || collapsedOffset == getExpandedOffset())
                            ? (float) (collapsedOffset - left) / (parentWidth - collapsedOffset)
                            : (float) (collapsedOffset - left) / (collapsedOffset - getExpandedOffset());
            for (int i = 0; i < callbacks.size(); i++) {
                callbacks.get(i).onSlide(sheet, slideOffset);
            }
        }
    }

    @VisibleForTesting
    int getPeekWidthMin() {
        return peekWidthMin;
    }

    /**
     * Disables the shaped corner {@link ShapeAppearanceModel} interpolation transition animations.
     * Will have no effect unless the sheet utilizes a {@link MaterialShapeDrawable} with set shape
     * theming properties. Only For use in UI testing.
     */
    @VisibleForTesting
    public void disableShapeAnimations() {
        // Sets the shape value animator to null, prevents animations from occuring during testing.
        interpolatorAnimator = null;
    }

    private class SettleRunnable implements Runnable {

        private final View view;

        private boolean isPosted;

        @State
        int targetState;

        SettleRunnable(View view, @State int targetState) {
            this.view = view;
            this.targetState = targetState;
        }

        @Override
        public void run() {
            if (viewDragHelper != null && viewDragHelper.continueSettling(true)) {
                ViewCompat.postOnAnimation(view, this);
            } else {
                setStateInternal(targetState);
            }
            this.isPosted = false;
        }
    }

    /**
     * State persisted across instances
     */
    protected static class SavedState extends AbsSavedState {
        @State
        final int state;
        int peekWidth;
        boolean fitToContents;
        boolean hideable;
        boolean skipCollapsed;

        public SavedState(@NonNull Parcel source) {
            this(source, null);
        }

        public SavedState(@NonNull Parcel source, ClassLoader loader) {
            super(source, loader);
            //noinspection ResourceType
            state = source.readInt();
            peekWidth = source.readInt();
            fitToContents = source.readInt() == 1;
            hideable = source.readInt() == 1;
            skipCollapsed = source.readInt() == 1;
        }

        public SavedState(Parcelable superState, @NonNull RightSheetBehavior<?> behavior) {
            super(superState);
            this.state = behavior.state;
            this.peekWidth = behavior.peekWidth;
            this.fitToContents = behavior.fitToContents;
            this.hideable = behavior.hideable;
            this.skipCollapsed = behavior.skipCollapsed;
        }

        /**
         * This constructor does not respect flags: {@link RightSheetBehavior#SAVE_PEEK_WIDTH}, {@link
         * RightSheetBehavior#SAVE_FIT_TO_CONTENTS}, {@link RightSheetBehavior#SAVE_HIDEABLE}, {@link
         * RightSheetBehavior#SAVE_SKIP_COLLAPSED}. It is as if {@link RightSheetBehavior#SAVE_NONE}
         * were set.
         *
         * @deprecated Use {@link SavedState(Parcelable, RightSheetBehavior )} instead.
         */
        @Deprecated
        public SavedState(Parcelable superstate, int state) {
            super(superstate);
            this.state = state;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(state);
            out.writeInt(peekWidth);
            out.writeInt(fitToContents ? 1 : 0);
            out.writeInt(hideable ? 1 : 0);
            out.writeInt(skipCollapsed ? 1 : 0);
        }

        public static final Creator<SavedState> CREATOR =
                new ClassLoaderCreator<SavedState>() {
                    @NonNull
                    @Override
                    public SavedState createFromParcel(@NonNull Parcel in, ClassLoader loader) {
                        return new SavedState(in, loader);
                    }

                    @Nullable
                    @Override
                    public SavedState createFromParcel(@NonNull Parcel in) {
                        return new SavedState(in, null);
                    }

                    @NonNull
                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }

    /**
     * A utility function to get the {@link RightSheetBehavior} associated with the {@code view}.
     *
     * @param view The {@link View} with {@link RightSheetBehavior}.
     * @return The {@link RightSheetBehavior} associated with the {@code view}.
     */
    @NonNull
    @SuppressWarnings("unchecked")
    public static <V extends View> RightSheetBehavior<V> from(@NonNull V view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof LayoutParams)) {
            throw new IllegalArgumentException("The view is not a child of CoordinatorLayout");
        }
        CoordinatorLayout.Behavior<?> behavior =
                ((LayoutParams) params).getBehavior();
        if (!(behavior instanceof RightSheetBehavior)) {
            throw new IllegalArgumentException("The view is not associated with RightSheetBehavior");
        }
        return (RightSheetBehavior<V>) behavior;
    }

    /**
     * Sets whether the RightSheet should update the accessibility status of its {@link *
     * CoordinatorLayout} siblings when expanded.
     *
     * <p>Set this to true if the expanded state of the sheet blocks access to siblings (e.g., when
     * the sheet expands over the full screen).
     */
    public void setUpdateImportantForAccessibilityOnSiblings(
            boolean updateImportantForAccessibilityOnSiblings) {
        this.updateImportantForAccessibilityOnSiblings = updateImportantForAccessibilityOnSiblings;
    }

    private void updateImportantForAccessibility(boolean expanded) {
        if (viewRef == null) {
            return;
        }

        ViewParent viewParent = viewRef.get().getParent();
        if (!(viewParent instanceof CoordinatorLayout)) {
            return;
        }

        CoordinatorLayout parent = (CoordinatorLayout) viewParent;
        final int childCount = parent.getChildCount();
        if ((VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) && expanded) {
            if (importantForAccessibilityMap == null) {
                importantForAccessibilityMap = new HashMap<>(childCount);
            } else {
                // The important for accessibility values of the child views have been saved already.
                return;
            }
        }

        for (int i = 0; i < childCount; i++) {
            final View child = parent.getChildAt(i);
            if (child == viewRef.get()) {
                continue;
            }

            if (expanded) {
                // Saves the important for accessibility value of the child view.
                if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
                    importantForAccessibilityMap.put(child, child.getImportantForAccessibility());
                }
                if (updateImportantForAccessibilityOnSiblings) {
                    ViewCompat.setImportantForAccessibility(
                            child, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                }
            } else {
                if (updateImportantForAccessibilityOnSiblings
                        && importantForAccessibilityMap != null
                        && importantForAccessibilityMap.containsKey(child)) {
                    // Restores the original important for accessibility value of the child view.
                    ViewCompat.setImportantForAccessibility(child, importantForAccessibilityMap.get(child));
                }
            }
        }

        if (!expanded) {
            importantForAccessibilityMap = null;
        }
    }

    @SuppressLint("SwitchIntDef")
    private void updateAccessibilityActions() {
        if (viewRef == null) {
            return;
        }
        V child = viewRef.get();
        if (child == null) {
            return;
        }
        ViewCompat.removeAccessibilityAction(child, AccessibilityNodeInfoCompat.ACTION_COLLAPSE);
        ViewCompat.removeAccessibilityAction(child, AccessibilityNodeInfoCompat.ACTION_EXPAND);
        ViewCompat.removeAccessibilityAction(child, AccessibilityNodeInfoCompat.ACTION_DISMISS);

        if (hideable && state != STATE_HIDDEN) {
            addAccessibilityActionForState(child, AccessibilityActionCompat.ACTION_DISMISS, STATE_HIDDEN);
        }

        switch (state) {
            case STATE_EXPANDED: {
                int nextState = fitToContents ? STATE_COLLAPSED : STATE_HALF_EXPANDED;
                addAccessibilityActionForState(
                        child, AccessibilityActionCompat.ACTION_COLLAPSE, nextState);
                break;
            }
            case STATE_HALF_EXPANDED: {
                addAccessibilityActionForState(
                        child, AccessibilityActionCompat.ACTION_COLLAPSE, STATE_COLLAPSED);
                addAccessibilityActionForState(
                        child, AccessibilityActionCompat.ACTION_EXPAND, STATE_EXPANDED);
                break;
            }
            case STATE_COLLAPSED: {
                int nextState = fitToContents ? STATE_EXPANDED : STATE_HALF_EXPANDED;
                addAccessibilityActionForState(child, AccessibilityActionCompat.ACTION_EXPAND, nextState);
                break;
            }
            default: // fall out
        }
    }

    private void addAccessibilityActionForState(
            V child, AccessibilityActionCompat action, final int state) {
        ViewCompat.replaceAccessibilityAction(
                child,
                action,
                null,
                new AccessibilityViewCommand() {
                    @Override
                    public boolean perform(@NonNull View view, @Nullable CommandArguments arguments) {
                        setState(state);
                        return true;
                    }
                });
    }
}
