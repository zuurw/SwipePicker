package one.xcorp.widget.swipepicker

import android.animation.*
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.TypedArray
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.Parcel
import android.os.Parcelable
import android.support.annotation.ColorInt
import android.support.v4.graphics.drawable.DrawableCompat
import android.support.v4.view.GestureDetectorCompat
import android.support.v4.view.ViewCompat
import android.support.v4.widget.TextViewCompat
import android.support.v7.widget.AppCompatTextView
import android.text.InputFilter
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.text.method.KeyListener
import android.util.AttributeSet
import android.view.*
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_UP
import android.view.SoundEffectConstants.CLICK
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.*
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import one.xcorp.widget.swipepicker.SwipeHandler.Params
import java.io.Serializable
import java.text.NumberFormat
import java.util.*

class SwipePicker : LinearLayout {

    companion object {
        private const val ANIMATION_DURATION = 200L
        private const val DELAY_SHOW_PRESS = 200
    }

    // <editor-fold desc="Properties">
    var hint: CharSequence?
        get() = hintTextView.text
        set(value) {
            hintTextView.text = value
        }
    var allowDeactivate = true
    var hintTextColor: ColorStateList
        get() = hintTextView.textColors
        set(value) = hintTextView.setTextColor(value)
    var inputTextColor: ColorStateList
        get() = inputEditText.textColors
        set(value) = inputEditText.setTextColor(value)
    var backgroundInput: Drawable?
        get() = inputAreaView.background
        set(value) {
            // clear tint on previously background
            val background = backgroundInput
            if (background != null) {
                DrawableCompat.setTintList(background, null)
                DrawableCompat.setTintMode(background, PorterDuff.Mode.SRC_IN)
            }
            // set new background and invalidate tint
            ViewCompat.setBackground(inputAreaView, value)
            backgroundInputTint = backgroundInputTint
            backgroundInputTintMode = backgroundInputTintMode
        }
    var backgroundInputTint: ColorStateList? = null
        set(value) {
            field = value
            backgroundInput?.let { DrawableCompat.setTintList(it, value) }
        }
    var backgroundInputTintMode = PorterDuff.Mode.SRC_IN
        set(value) {
            field = value
            backgroundInput?.let { DrawableCompat.setTintMode(it, value) }
        }
    var manualInput = true
        set(enable) {
            if (!enable) {
                isSelected = false
            }
            field = enable
        }
    var inputFilters: Array<InputFilter>
        get() = inputEditText.filters
        set(value) {
            inputEditText.filters = value
            invalidateValue()
        }
    var inputType: Int
        get() = inputEditText.inputType
        set(value) {
            inputEditText.inputType = value
        }
    var keyListener: KeyListener?
        get() = inputEditText.keyListener
        set(value) {
            inputEditText.keyListener = value
        }
    var stickyScale = false
        set(enable) {
            field = enable
            if (enable && scale != null) {
                value = value // stick value to scale
            }
        }
    var scale: List<Float>? = null
        set(scale) {
            if (scale == null) {
                field = null
                return
            }

            require(!scale.isEmpty() && scale.windowed(2).all { (a, b) -> a < b }) {
                "Invalid values scale format. An array must have one or more elements in ascending order."
            }

            field = scale.toList()
            if (stickyScale) {
                value = value // stick value to scale
            }
        }
    var minValue = -Float.MAX_VALUE
        set(min) {
            field = min
            value = value // check value restriction
        }
    var maxValue = Float.MAX_VALUE
        set(max) {
            field = max
            value = value // check value restriction
        }
    var step = 1f
        set(step) {
            field = step
            if (stickyScale && scale != null) {
                value = value // stick value to scale
            }
        }
    var value = 1f
        set(value) {
            val oldValue = field
            var newValue = value

            if (stickyScale) {
                newValue = stickToScale(newValue)
            }

            if (newValue < minValue) {
                newValue = minValue
            } else if (newValue > maxValue) {
                newValue = maxValue
            }

            if (newValue != oldValue) {
                field = newValue
                invalidateValue()

                valueChangeListener?.onValueChanged(this, oldValue, newValue)
            }
        }
    val hoverView by lazy { createHoverView() }
    // </editor-fold>

    private val windowManager: WindowManager
    private val gestureDetector: GestureDetectorCompat

    private val hintTextView by lazy { findViewById<AppCompatTextView>(android.R.id.hint) }
    private val inputAreaView by lazy { findViewById<View>(android.R.id.inputArea) }
    private val inputEditText by lazy { findViewById<EditText>(android.R.id.input) }

    private val numberFormat = NumberFormat.getInstance(Locale.US).apply { isGroupingUsed = false }
    private val swipeHandler = SwipeHandler()
    private var activated = false
    private val inputAreaPosition = IntArray(2)
    private var hintAnimation: AnimatorSet? = null
    private val hoverViewMargin = resources.getDimensionPixelSize(R.dimen.hoverView_margin)
    private var hoverViewStyle = R.style.XcoRp_Style_SwipePicker_HoverView
    private val hoverViewLayoutParams by lazy { createHoverViewLayoutParams() }

    private var valueTransformer: ValueTransformer = object : ValueTransformer {}
    private var stateChangeListener: OnStateChangeListener? = null
    private var valueChangeListener: OnValueChangeListener? = null
    private var swipeListener: OnSwipeListener = object : OnSwipeListener {}

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.swipePicker)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            this(context, attrs, defStyleAttr, R.style.XcoRp_Widget_SwipePicker)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
            super(context, attrs, defStyleAttr) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        gestureDetector = GestureDetectorCompat(context, GestureListener())
        gestureDetector.setIsLongpressEnabled(false)

        orientation = VERTICAL
        isChildrenDrawingOrderEnabled = true

        inflate(context, R.layout.swipe_picker, this)
        obtainStyledAttributes(context, attrs, defStyleAttr, defStyleRes)

        inputAreaView.setOnTouchListener(::onTouch)
        inputEditText.setOnBackPressedListener(::onInputCancel)
        inputEditText.setOnEditorActionListener(::onInputDone)

        invalidateValue()
    }

    // It is required that the input area does not hide a hint.
    override fun getChildDrawingOrder(childCount: Int, i: Int) =
            if (i == 0) 1 else if (i == 1) 0 else i

    private fun calculateHintPosition(activated: Boolean): Float {
        if (activated) return 0f

        val scaledHeight = hintTextView.height *
                inputEditText.height / hintTextView.height.toFloat()
        val scaledVerticalOffset = (hintTextView.height - scaledHeight) / 2

        return scaledHeight + scaledVerticalOffset + inputEditText.top
    }

    private fun calculateFontScale(activated: Boolean) =
            if (activated) 1f else inputEditText.textSize / hintTextView.textSize

    private fun createHoverView() = HoverView(context,
            defStyleRes = hoverViewStyle).apply { alpha = 0f }

    private fun createHoverViewLayoutParams() = WindowManager.LayoutParams().apply {
        width = MATCH_PARENT
        height = MATCH_PARENT
        format = PixelFormat.TRANSLUCENT
        flags = (FLAG_LAYOUT_IN_SCREEN or FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE)
        type = TYPE_APPLICATION_PANEL
    }

    private fun obtainStyledAttributes(
            context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) {
        val typedArray = context.obtainStyledAttributes(
                attrs, R.styleable.SwipePicker, defStyleAttr, defStyleRes)

        minimumWidth = typedArray.getDimensionPixelSize(
                R.styleable.SwipePicker_android_minWidth,
                resources.getDimensionPixelSize(R.dimen.swipePicker_minWidth))
        setChildPadding(typedArray.getDimensionPixelSize(R.styleable.SwipePicker_childPadding,
                resources.getDimensionPixelSize(R.dimen.swipePicker_childPadding)))
        hint = typedArray.getString(R.styleable.SwipePicker_android_hint)
        activated = typedArray.getBoolean(
                R.styleable.SwipePicker_android_state_activated, activated)
        allowDeactivate = typedArray
                .getBoolean(R.styleable.SwipePicker_allowDeactivate, allowDeactivate)
        setHintTextAppearance(typedArray.getResourceId(
                R.styleable.SwipePicker_hintTextAppearance,
                R.style.XcoRp_TextAppearance_SwipePicker_Hint))
        setInputTextAppearance(typedArray.getResourceId(
                R.styleable.SwipePicker_inputTextAppearance,
                R.style.XcoRp_TextAppearance_SwipePicker_Input))
        backgroundInput = typedArray.getDrawable(R.styleable.SwipePicker_backgroundInput)
        if (typedArray.hasValue(R.styleable.SwipePicker_backgroundInputTint)) {
            backgroundInputTint = typedArray
                    .getColorStateList(R.styleable.SwipePicker_backgroundInputTint)
        }
        if (typedArray.hasValue(R.styleable.SwipePicker_backgroundInputTintMode)) {
            backgroundInputTintMode = typedArray
                    .getTintMode(R.styleable.SwipePicker_backgroundInputTintMode, backgroundInputTintMode)
        }
        manualInput = typedArray.getBoolean(R.styleable.SwipePicker_manualInput, manualInput)
        setMaxLength(typedArray.getInt(R.styleable.SwipePicker_android_maxLength,
                resources.getInteger(R.integer.swipePicker_maxLength)))
        inputType = typedArray.getInt(
                R.styleable.SwipePicker_android_inputType, InputType.TYPE_CLASS_NUMBER)
        if (typedArray.hasValue(R.styleable.SwipePicker_android_digits)) {
            keyListener = DigitsKeyListener
                    .getInstance(typedArray.getString(R.styleable.SwipePicker_android_digits))
        }
        stickyScale = typedArray.getBoolean(R.styleable.SwipePicker_stickyScale, stickyScale)
        if (typedArray.hasValue(R.styleable.SwipePicker_scale)) {
            scale = typedArray.getFloatArray(R.styleable.SwipePicker_scale).toList()
        }
        minValue = typedArray.getFloat(R.styleable.SwipePicker_minValue, minValue)
        maxValue = typedArray.getFloat(R.styleable.SwipePicker_maxValue, maxValue)
        require(minValue < maxValue) { "The minimum value is greater than the maximum." }
        step = typedArray.getFloat(R.styleable.SwipePicker_step, step)
        value = typedArray.getFloat(R.styleable.SwipePicker_value, value)
        hoverViewStyle = typedArray.getResourceId(
                R.styleable.SwipePicker_hoverViewStyle, hoverViewStyle)

        typedArray.recycle()
    }

    /**
     * Read floating array from resources xml.
     *
     * @param index Index of attribute to retrieve.
     * @return Read floating array. Throws exception if
     * the given resId does not exist or can't get array.
     */
    private fun TypedArray.getFloatArray(index: Int): FloatArray {
        var typedArray: TypedArray? = null
        return try {
            typedArray = resources.obtainTypedArray(getResourceId(index, -1))

            val result = FloatArray(typedArray.length())
            for (i in 0 until typedArray.length()) {
                result[i] = typedArray.getFloat(i, 0f)
            }

            if (result.isEmpty()) throw IllegalStateException("Array is empty.") else result
        } catch (e: Throwable) {
            throw IllegalArgumentException("Can't get float array by specified resource ID.", e)
        } finally {
            typedArray?.recycle()
        }
    }

    private fun TypedArray.getTintMode(resId: Int, default: PorterDuff.Mode): PorterDuff.Mode {
        return when (getInt(resId, -1)) {
            3 -> PorterDuff.Mode.SRC_OVER
            5 -> PorterDuff.Mode.SRC_IN
            9 -> PorterDuff.Mode.SRC_ATOP
            14 -> PorterDuff.Mode.MULTIPLY
            15 -> PorterDuff.Mode.SCREEN
            16 -> PorterDuff.Mode.ADD
            else -> default
        }
    }

    fun setChildPadding(padding: Int) {
        hintTextView.setPadding(padding, padding, padding, padding)
        inputEditText.setPadding(padding, padding, padding, padding)
    }

    fun setHintTextColor(@ColorInt color: Int) = hintTextView.setTextColor(color)

    fun setHintTextAppearance(resId: Int) = TextViewCompat.setTextAppearance(hintTextView, resId)

    fun setInputTextColor(@ColorInt color: Int) = inputEditText.setTextColor(color)

    fun setInputTextAppearance(resId: Int) = TextViewCompat.setTextAppearance(inputEditText, resId)

    fun setBackgroundInputTint(@ColorInt tintColor: Int) {
        backgroundInputTint = ColorStateList.valueOf(tintColor)
    }

    fun setMaxLength(length: Int) {
        inputFilters = inputFilters
                .filter { f -> f !is InputFilter.LengthFilter }
                .toMutableList()
                .apply { add(0, InputFilter.LengthFilter(length)) }
                .toTypedArray()
    }

    fun getInputText(): String = inputEditText.text.toString()

    fun setValue(value: CharSequence): Boolean {
        val result = valueTransformer.transform(this, value.toString())
        if (result != null) {
            this.value = result
            return true
        }
        return false
    }

    fun setValueTransformer(transformer: ValueTransformer) {
        valueTransformer = transformer
        invalidateValue()
    }

    fun setOnStateChangeListener(listener: (isActivated: Boolean) -> Unit) =
            setOnStateChangeListener(object : OnStateChangeListener {
                override fun onStateChanged(view: SwipePicker, isActivated: Boolean) {
                    listener(isActivated)
                }
            })

    fun setOnStateChangeListener(listener: OnStateChangeListener?) {
        stateChangeListener = listener
    }

    fun setOnValueChangeListener(listener: (value: Float) -> Unit) =
            setOnValueChangeListener(object : OnValueChangeListener {
                override fun onValueChanged(view: SwipePicker, oldValue: Float, newValue: Float) {
                    listener(newValue)
                }
            })

    fun setOnValueChangeListener(listener: OnValueChangeListener?) {
        valueChangeListener = listener
    }

    fun setOnSwipeListener(listener: (value: Float, division: Int) -> Float) =
            setOnSwipeListener(object : OnSwipeListener {
                override fun onSwipe(view: SwipePicker, value: Float, division: Int): Float {
                    return listener(value, division)
                }
            })

    fun setOnSwipeListener(listener: OnSwipeListener) {
        swipeListener = listener
    }

    override fun onSaveInstanceState(): Parcelable {
        hintAnimation?.end() // end animation before save
        val state = SavedState(super.onSaveInstanceState())

        state.allowDeactivate = allowDeactivate
        state.manualInput = manualInput
        state.scale = scale
        state.minValue = minValue
        state.maxValue = maxValue
        state.step = step
        state.value = value
        state.activated = isActivated
        state.selected = isSelected

        return state
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }

        allowDeactivate = state.allowDeactivate
        manualInput = state.manualInput
        scale = state.scale
        minValue = state.minValue
        maxValue = state.maxValue
        step = state.step
        value = state.value
        isActivated = state.activated
        isSelected = state.selected

        hintAnimation?.end() // end animation before restore
        super.onRestoreInstanceState(state.superState)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        if (changed) {
            val instantly = hintAnimation?.isRunning != true

            animateHint(isActivated)
            if (instantly) {
                hintAnimation?.end()
            }

            if (isPressed) {
                invalidateHoverViewPosition()
            }
        }
    }

    override fun performClick(): Boolean {
        if (!super.performClick()) {
            playSoundEffect(CLICK)
        }
        isSelected = true
        return true
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        val result = gestureDetector.onTouchEvent(event)
        if (event.action == ACTION_UP || event.action == ACTION_CANCEL) {
            handler.removeCallbacksAndMessages(gestureDetector)
            view.playSoundEffect(CLICK)
            isPressed = false
        }
        return result
    }

    override fun isActivated() = activated

    override fun setActivated(activated: Boolean) {
        if (activated == isActivated) return

        if (!activated) {
            isPressed = false
            isSelected = false
        }

        animateHint(activated)
        this.activated = activated

        stateChangeListener?.onStateChanged(this, activated)
    }

    private fun animateHint(activated: Boolean) {
        hintAnimation?.removeAllListeners()
        hintAnimation?.cancel()

        val yPosition = calculateHintPosition(activated)
        val fontScale = calculateFontScale(activated)

        hintAnimation = if (activated) {
            createHintAnimation(yPosition, fontScale).addEndListener {
                super.setActivated(activated)
                setInputVisible(true)
            }
        } else {
            createHintAnimation(yPosition, fontScale).addStartListener {
                super.setActivated(activated)
                setInputVisible(false)
            }
        }
        hintAnimation?.start()
    }

    private fun createHintAnimation(y: Float, scale: Float): AnimatorSet {
        val animatorSet = AnimatorSet()

        val animMove = ObjectAnimator.ofFloat(hintTextView, "translationY", y)
        val animScale = ObjectAnimator.ofPropertyValuesHolder(hintTextView,
                PropertyValuesHolder.ofFloat("scaleX", scale),
                PropertyValuesHolder.ofFloat("scaleY", scale))

        animatorSet.playTogether(animMove, animScale)
        animatorSet.duration = ANIMATION_DURATION

        return animatorSet
    }

    override fun setSelected(selected: Boolean) {
        if (!manualInput || selected == isSelected) return

        if (selected && !isActivated) {
            isActivated = true
        }

        super.setSelected(selected)
        invalidateValue()

        if (inputEditText.visibility == View.VISIBLE) {
            setInputEnable(selected)
        }
    }

    private fun setInputVisible(visible: Boolean) {
        if (visible) {
            inputEditText.visibility = View.VISIBLE
            setInputEnable(isSelected)
        } else {
            setInputEnable(false)
            inputEditText.visibility = View.INVISIBLE
        }
    }

    private fun setInputEnable(enable: Boolean) {
        if (inputEditText.isEnabled == enable) {
            return
        }

        inputEditText.isEnabled = enable
        if (enable) {
            inputEditText.requestFocus()
            inputEditText.selectAll() // selectAllOnFocus not always working when rotation
            inputEditText.showKeyboard()
        } else {
            inputEditText.clearFocus()
            inputEditText.hideKeyBoard()
        }
    }

    private fun onInputCancel(): Boolean {
        if (isSelected) {
            isSelected = false
            return true
        }
        return false
    }

    private fun onInputDone(view: TextView, actionId: Int, event: KeyEvent?): Boolean {
        if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
            playSoundEffect(CLICK)
            val result = valueTransformer.transform(this, view.text.toString())
            if (result == null) {
                inputEditText.selectAll()
            } else {
                value = result
                isSelected = false
            }
            return true
        }
        return false
    }

    override fun setPressed(pressed: Boolean) {
        if (pressed == isPressed) return

        if (pressed && !isActivated) {
            hintTextView.alpha = 0f // hide hint immediately

            isActivated = true
            hintAnimation?.end()
        }

        super.setPressed(pressed)
        if (pressed) {
            showHoverView()
        } else {
            hideHoverView()
        }
    }

    private fun showHoverView() {
        hoverView.text = inputEditText.text
        invalidateHoverViewPosition()

        hintTextView.animate().alpha(0f).setDuration(ANIMATION_DURATION).start()
        hoverView.animate().alpha(1f).setDuration(ANIMATION_DURATION)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator) {
                        if (hoverView.parent == null) {
                            windowManager.addView(hoverView, hoverViewLayoutParams)
                        }
                    }
                }).start()
    }

    private fun hideHoverView() {
        // animate with duration 0 to undo the previous animation
        hintTextView.animate().alpha(1f).setDuration(0).start()
        hoverView.animate().alpha(0f).setDuration(0)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (hoverView.parent != null) {
                            windowManager.removeViewImmediate(hoverView)
                        }
                    }
                }).start()
    }

    private fun invalidateHoverViewPosition() {
        hoverView.measure(MeasureSpec.EXACTLY, MeasureSpec.EXACTLY)
        inputAreaView.getLocationOnScreen(inputAreaPosition)

        hoverView.x = inputAreaPosition[0] +
                inputAreaView.width / 2f - hoverView.measuredWidth / 2f
        hoverView.y = inputAreaPosition[1] -
                hoverView.measuredHeight - hoverViewMargin.toFloat()
    }

    private fun stickToScale(value: Float): Float {
        scale?.let { scale ->
            val index = scale.binarySearch(value)
            if (index >= 0) return value

            val insertion = -index - 1
            return when (insertion) {
            // outside value from left side
                0 -> swipeHandler.closestInBoundary(scale.first(), step, minValue, value)
            // outside value from right side
                scale.size -> swipeHandler.closestInBoundary(scale.last(), step, maxValue, value)
            // value on scale
                else -> swipeHandler.closestValue(scale[insertion - 1], scale[insertion], value)
            }
        }
        return value
    }

    private fun invalidateValue() {
        if (isSelected) return

        inputEditText.setText(valueTransformer.transform(this, value))
        if (isPressed) {
            // get the value from input to take into account the maximum length
            hoverView.text = inputEditText.text
            invalidateHoverViewPosition()
        }
    }

    interface ValueTransformer {

        /**
         * Transform value from string to float.
         *
         * @param value Manual entered value.
         * @return Converted internal value or {@code null}.
         * If null then input mode don't disable and value not apply.
         */
        fun transform(view: SwipePicker, value: String): Float? {
            return value.toFloatOrNull() ?: view.value
        }

        /**
         * Transform value from float to string.
         *
         * @param value Internal value.
         * @return Converted display value or {@code null}.
         * If null then set empty value.
         */
        fun transform(view: SwipePicker, value: Float): String? {
            return view.numberFormat.format(value)
        }
    }

    interface OnStateChangeListener {

        /**
         * The listener is called every time the activate state changes.
         *
         * @param view SwipePicker of initiating event.
         * @param isActivated Current activated state.
         */
        fun onStateChanged(view: SwipePicker, isActivated: Boolean)
    }

    interface OnValueChangeListener {

        /**
         * The listener is called every time the value changes.
         *
         * @param view SwipePicker of initiating event.
         * @param oldValue Old value.
         * @param newValue New value.
         */
        fun onValueChanged(view: SwipePicker, oldValue: Float, newValue: Float)
    }

    interface OnSwipeListener {

        /**
         * Handling a swipe gesture.
         *
         * @param view SwipePicker of initiating event.
         * @param value The value from which need moved.
         * @param division The number of divisions that have moved.
         * @return The calculated value after the gesture processing which must be set to the view.
         */
        fun onSwipe(view: SwipePicker, value: Float, division: Int): Float = with(view) {
            val params = Params(scale, step)
            return swipeHandler.onSwipe(params, value, division)
        }
    }

    private class SavedState : BaseSavedState {

        var allowDeactivate = true
        var manualInput = true
        var scale: List<Float>? = null
        var minValue = -Float.MAX_VALUE
        var maxValue = Float.MAX_VALUE
        var step = 1f
        var value = 1f
        var activated = false
        var selected = false

        constructor(superState: Parcelable) : super(superState)

        constructor(parcel: Parcel) : super(parcel) {
            allowDeactivate = parcel.readByte() != 0.toByte()
            manualInput = parcel.readByte() != 0.toByte()
            @Suppress("UNCHECKED_CAST")
            scale = parcel.readSerializable() as List<Float>?
            minValue = parcel.readFloat()
            maxValue = parcel.readFloat()
            step = parcel.readFloat()
            value = parcel.readFloat()
            activated = parcel.readByte() != 0.toByte()
            selected = parcel.readByte() != 0.toByte()
        }

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            super.writeToParcel(parcel, flags)
            parcel.writeByte(if (allowDeactivate) 1 else 0)
            parcel.writeByte(if (manualInput) 1 else 0)
            parcel.writeSerializable(scale as Serializable?)
            parcel.writeFloat(minValue)
            parcel.writeFloat(maxValue)
            parcel.writeFloat(step)
            parcel.writeFloat(value)
            parcel.writeByte(if (activated) 1 else 0)
            parcel.writeByte(if (selected) 1 else 0)
        }

        companion object CREATOR : Parcelable.Creator<SavedState> {
            override fun createFromParcel(parcel: Parcel): SavedState {
                return SavedState(parcel)
            }

            override fun newArray(size: Int): Array<SavedState?> {
                return arrayOfNulls(size)
            }
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        private val swipeThreshold = resources.getDimensionPixelSize(R.dimen.swipePicker_swipeThreshold)

        private var isShowPress = false

        private var initialValue = 0f
        private var previousDivision = 0

        override fun onDown(event: MotionEvent): Boolean {
            isShowPress = false

            initialValue = value
            previousDivision = 0

            handler.postAtTime(::onShowPress,
                    gestureDetector, event.downTime + DELAY_SHOW_PRESS)

            return true
        }

        /**
         * Use it because SimpleOnGestureListener#onShowPress(MotionEvent e)
         * causes flicker and wrong behavior.
         */
        fun onShowPress() {
            isPressed = true
            isShowPress = true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (!isShowPress) {
                isSelected = true
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (allowDeactivate) {
                isActivated = false
                return true
            }
            return false
        }

        override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val division = Math.round((e2.x - e1.x) / swipeThreshold)

            if (previousDivision != division) {
                if (!isPressed) onShowPress()
                value = swipeListener.onSwipe(this@SwipePicker, initialValue, division)
            }

            previousDivision = division
            return isPressed
        }
    }

    private fun AnimatorSet.addStartListener(listener: () -> Unit): AnimatorSet {
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator?) {
                listener()
            }
        })
        return this
    }

    private fun AnimatorSet.addEndListener(listener: () -> Unit): AnimatorSet {
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                listener()
            }
        })
        return this
    }
}
