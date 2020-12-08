/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.media

import android.content.Context
import android.content.res.Configuration
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.R
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.animation.MeasurementOutput
import com.android.systemui.util.animation.TransitionLayout
import com.android.systemui.util.animation.TransitionLayoutController
import com.android.systemui.util.animation.TransitionViewState
import javax.inject.Inject

/**
 * A class responsible for controlling a single instance of a media player handling interactions
 * with the view instance and keeping the media view states up to date.
 */
class MediaViewController @Inject constructor(
    context: Context,
    private val configurationController: ConfigurationController,
    private val mediaHostStatesManager: MediaHostStatesManager
) {

    companion object {
        @JvmField
        val GUTS_ANIMATION_DURATION = 500L
    }

    /**
     * A listener when the current dimensions of the player change
     */
    lateinit var sizeChangedListener: () -> Unit
    private var firstRefresh: Boolean = true
    private var transitionLayout: TransitionLayout? = null
    private val layoutController = TransitionLayoutController()
    private var animationDelay: Long = 0
    private var animationDuration: Long = 0
    private var animateNextStateChange: Boolean = false
    private val measurement = MeasurementOutput(0, 0)

    /**
     * A map containing all viewStates for all locations of this mediaState
     */
    private val viewStates: MutableMap<CacheKey, TransitionViewState?> = mutableMapOf()

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    @MediaLocation
    private var currentEndLocation: Int = -1

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    @MediaLocation
    private var currentStartLocation: Int = -1

    /**
     * The progress of the transition or 1.0 if there is no transition happening
     */
    private var currentTransitionProgress: Float = 1.0f

    /**
     * A temporary state used to store intermediate measurements.
     */
    private val tmpState = TransitionViewState()

    /**
     * A temporary state used to store intermediate measurements.
     */
    private val tmpState2 = TransitionViewState()

    /**
     * A temporary state used to store intermediate measurements.
     */
    private val tmpState3 = TransitionViewState()

    /**
     * A temporary cache key to be used to look up cache entries
     */
    private val tmpKey = CacheKey()

    /**
     * The current width of the player. This might not factor in case the player is animating
     * to the current state, but represents the end state
     */
    var currentWidth: Int = 0
    /**
     * The current height of the player. This might not factor in case the player is animating
     * to the current state, but represents the end state
     */
    var currentHeight: Int = 0

    /**
     * Get the translationX of the layout
     */
    var translationX: Float = 0.0f
        private set
        get() {
            return transitionLayout?.translationX ?: 0.0f
        }

    /**
     * Get the translationY of the layout
     */
    var translationY: Float = 0.0f
        private set
        get() {
            return transitionLayout?.translationY ?: 0.0f
        }

    /**
     * A callback for RTL config changes
     */
    private val configurationListener = object : ConfigurationController.ConfigurationListener {
        override fun onConfigChanged(newConfig: Configuration?) {
            // Because the TransitionLayout is not always attached (and calculates/caches layout
            // results regardless of attach state), we have to force the layoutDirection of the view
            // to the correct value for the user's current locale to ensure correct recalculation
            // when/after calling refreshState()
            newConfig?.apply {
                if (transitionLayout?.rawLayoutDirection != layoutDirection) {
                    transitionLayout?.layoutDirection = layoutDirection
                    refreshState()
                }
            }
        }
    }

    /**
     * A callback for media state changes
     */
    val stateCallback = object : MediaHostStatesManager.Callback {
        override fun onHostStateChanged(
            @MediaLocation location: Int,
            mediaHostState: MediaHostState
        ) {
            if (location == currentEndLocation || location == currentStartLocation) {
                setCurrentState(currentStartLocation,
                        currentEndLocation,
                        currentTransitionProgress,
                        applyImmediately = false)
            }
        }
    }

    /**
     * The expanded constraint set used to render a expanded player. If it is modified, make sure
     * to call [refreshState]
     */
    val collapsedLayout = ConstraintSet()

    /**
     * The expanded constraint set used to render a collapsed player. If it is modified, make sure
     * to call [refreshState]
     */
    val expandedLayout = ConstraintSet()

    /**
     * Whether the guts are visible for the associated player.
     */
    var isGutsVisible = false
        private set

    init {
        collapsedLayout.load(context, R.xml.media_collapsed)
        expandedLayout.load(context, R.xml.media_expanded)
        mediaHostStatesManager.addController(this)
        layoutController.sizeChangedListener = { width: Int, height: Int ->
            currentWidth = width
            currentHeight = height
            sizeChangedListener.invoke()
        }
        configurationController.addCallback(configurationListener)
    }

    /**
     * Notify this controller that the view has been removed and all listeners should be destroyed
     */
    fun onDestroy() {
        mediaHostStatesManager.removeController(this)
        configurationController.removeCallback(configurationListener)
    }

    /**
     * Show guts with an animated transition.
     */
    fun openGuts() {
        if (isGutsVisible) return
        isGutsVisible = true
        animatePendingStateChange(GUTS_ANIMATION_DURATION, 0L)
        setCurrentState(currentStartLocation,
                currentEndLocation,
                currentTransitionProgress,
                applyImmediately = false)
    }

    /**
     * Close the guts for the associated player.
     *
     * @param immediate if `false`, it will animate the transition.
     */
    @JvmOverloads
    fun closeGuts(immediate: Boolean = false) {
        if (!isGutsVisible) return
        isGutsVisible = false
        if (!immediate) {
            animatePendingStateChange(GUTS_ANIMATION_DURATION, 0L)
        }
        setCurrentState(currentStartLocation,
                currentEndLocation,
                currentTransitionProgress,
                applyImmediately = immediate)
    }

    private fun ensureAllMeasurements() {
        val mediaStates = mediaHostStatesManager.mediaHostStates
        for (entry in mediaStates) {
            obtainViewState(entry.value)
        }
    }

    /**
     * Get the constraintSet for a given expansion
     */
    private fun constraintSetForExpansion(expansion: Float): ConstraintSet =
            if (expansion > 0) expandedLayout else collapsedLayout

    /**
     * Set the views to be showing/hidden based on the [isGutsVisible] for a given
     * [TransitionViewState].
     */
    private fun setGutsViewState(viewState: TransitionViewState) {
        PlayerViewHolder.controlsIds.forEach { id ->
            viewState.widgetStates.get(id)?.let { state ->
                // Make sure to use the unmodified state if guts are not visible
                state.alpha = if (isGutsVisible) 0f else state.alpha
                state.gone = if (isGutsVisible) true else state.gone
            }
        }
        PlayerViewHolder.gutsIds.forEach { id ->
            viewState.widgetStates.get(id)?.alpha = if (isGutsVisible) 1f else 0f
            viewState.widgetStates.get(id)?.gone = !isGutsVisible
        }
    }

    /**
     * Obtain a new viewState for a given media state. This usually returns a cached state, but if
     * it's not available, it will recreate one by measuring, which may be expensive.
     */
    private fun obtainViewState(state: MediaHostState?): TransitionViewState? {
        if (state == null || state.measurementInput == null) {
            return null
        }
        // Only a subset of the state is relevant to get a valid viewState. Let's get the cachekey
        var cacheKey = getKey(state, isGutsVisible, tmpKey)
        val viewState = viewStates[cacheKey]
        if (viewState != null) {
            // we already have cached this measurement, let's continue
            return viewState
        }
        // Copy the key since this might call recursively into it and we're using tmpKey
        cacheKey = cacheKey.copy()
        val result: TransitionViewState?
        if (transitionLayout != null) {
            // Let's create a new measurement
            if (state.expansion == 0.0f || state.expansion == 1.0f) {
                result = transitionLayout!!.calculateViewState(
                        state.measurementInput!!,
                        constraintSetForExpansion(state.expansion),
                        TransitionViewState())

                setGutsViewState(result)
                // We don't want to cache interpolated or null states as this could quickly fill up
                // our cache. We only cache the start and the end states since the interpolation
                // is cheap
                viewStates[cacheKey] = result
            } else {
                // This is an interpolated state
                val startState = state.copy().also { it.expansion = 0.0f }

                // Given that we have a measurement and a view, let's get (guaranteed) viewstates
                // from the start and end state and interpolate them
                val startViewState = obtainViewState(startState) as TransitionViewState
                val endState = state.copy().also { it.expansion = 1.0f }
                val endViewState = obtainViewState(endState) as TransitionViewState
                result = layoutController.getInterpolatedState(
                        startViewState,
                        endViewState,
                        state.expansion)
            }
        } else {
            result = null
        }
        return result
    }

    private fun getKey(state: MediaHostState, guts: Boolean, result: CacheKey): CacheKey {
        result.apply {
            heightMeasureSpec = state.measurementInput?.heightMeasureSpec ?: 0
            widthMeasureSpec = state.measurementInput?.widthMeasureSpec ?: 0
            expansion = state.expansion
            gutsVisible = guts
        }
        return result
    }

    /**
     * Attach a view to this controller. This may perform measurements if it's not available yet
     * and should therefore be done carefully.
     */
    fun attach(transitionLayout: TransitionLayout) {
        this.transitionLayout = transitionLayout
        layoutController.attach(transitionLayout)
        if (currentEndLocation == -1) {
            return
        }
        // Set the previously set state immediately to the view, now that it's finally attached
        setCurrentState(
                startLocation = currentStartLocation,
                endLocation = currentEndLocation,
                transitionProgress = currentTransitionProgress,
                applyImmediately = true)
    }

    /**
     * Obtain a measurement for a given location. This makes sure that the state is up to date
     * and all widgets know their location. Calling this method may create a measurement if we
     * don't have a cached value available already.
     */
    fun getMeasurementsForState(hostState: MediaHostState): MeasurementOutput? {
        val viewState = obtainViewState(hostState) ?: return null
        measurement.measuredWidth = viewState.width
        measurement.measuredHeight = viewState.height
        return measurement
    }

    /**
     * Set a new state for the controlled view which can be an interpolation between multiple
     * locations.
     */
    fun setCurrentState(
        @MediaLocation startLocation: Int,
        @MediaLocation endLocation: Int,
        transitionProgress: Float,
        applyImmediately: Boolean
    ) {
        currentEndLocation = endLocation
        currentStartLocation = startLocation
        currentTransitionProgress = transitionProgress

        val shouldAnimate = animateNextStateChange && !applyImmediately

        val endHostState = mediaHostStatesManager.mediaHostStates[endLocation] ?: return
        val startHostState = mediaHostStatesManager.mediaHostStates[startLocation]

        // Obtain the view state that we'd want to be at the end
        // The view might not be bound yet or has never been measured and in that case will be
        // reset once the state is fully available
        var endViewState = obtainViewState(endHostState) ?: return
        endViewState = updateViewStateToCarouselSize(endViewState, endLocation, tmpState2)!!
        layoutController.setMeasureState(endViewState)

        // If the view isn't bound, we can drop the animation, otherwise we'll execute it
        animateNextStateChange = false
        if (transitionLayout == null) {
            return
        }

        val result: TransitionViewState
        var startViewState = obtainViewState(startHostState)
        startViewState = updateViewStateToCarouselSize(startViewState, startLocation, tmpState3)

        if (!endHostState.visible) {
            // Let's handle the case where the end is gone first. In this case we take the
            // start viewState and will make it gone
            if (startViewState == null || startHostState == null || !startHostState.visible) {
                // the start isn't a valid state, let's use the endstate directly
                result = endViewState
            } else {
                // Let's get the gone presentation from the start state
                result = layoutController.getGoneState(startViewState,
                        startHostState.disappearParameters,
                        transitionProgress,
                        tmpState)
            }
        } else if (startHostState != null && !startHostState.visible) {
            // We have a start state and it is gone.
            // Let's get presentation from the endState
            result = layoutController.getGoneState(endViewState, endHostState.disappearParameters,
                    1.0f - transitionProgress,
                    tmpState)
        } else if (transitionProgress == 1.0f || startViewState == null) {
            // We're at the end. Let's use that state
            result = endViewState
        } else if (transitionProgress == 0.0f) {
            // We're at the start. Let's use that state
            result = startViewState
        } else {
            result = layoutController.getInterpolatedState(startViewState, endViewState,
                    transitionProgress, tmpState)
        }
        layoutController.setState(result, applyImmediately, shouldAnimate, animationDuration,
                animationDelay)
    }

    private fun updateViewStateToCarouselSize(
        viewState: TransitionViewState?,
        location: Int,
        outState: TransitionViewState
    ) : TransitionViewState? {
        val result = viewState?.copy(outState) ?: return null
        val overrideSize = mediaHostStatesManager.carouselSizes[location]
        overrideSize?.let {
            // To be safe we're using a maximum here. The override size should always be set
            // properly though.
            result.height = Math.max(it.measuredHeight, result.height)
            result.width = Math.max(it.measuredWidth, result.width)
        }
        return result
    }

    /**
     * Retrieves the [TransitionViewState] and [MediaHostState] of a [@MediaLocation].
     * In the event of [location] not being visible, [locationWhenHidden] will be used instead.
     *
     * @param location Target
     * @param locationWhenHidden Location that will be used when the target is not
     * [MediaHost.visible]
     * @return State require for executing a transition, and also the respective [MediaHost].
     */
    private fun obtainViewStateForLocation(@MediaLocation location: Int): TransitionViewState? {
        val mediaHostState = mediaHostStatesManager.mediaHostStates[location] ?: return null
        return obtainViewState(mediaHostState)
    }

    /**
     * Notify that the location is changing right now and a [setCurrentState] change is imminent.
     * This updates the width the view will me measured with.
     */
    fun onLocationPreChange(@MediaLocation newLocation: Int) {
        obtainViewStateForLocation(newLocation)?.let {
            layoutController.setMeasureState(it)
        }
    }

    /**
     * Request that the next state change should be animated with the given parameters.
     */
    fun animatePendingStateChange(duration: Long, delay: Long) {
        animateNextStateChange = true
        animationDuration = duration
        animationDelay = delay
    }

    /**
     * Clear all existing measurements and refresh the state to match the view.
     */
    fun refreshState() {
        // Let's clear all of our measurements and recreate them!
        viewStates.clear()
        if (firstRefresh) {
            // This is the first bind, let's ensure we pre-cache all measurements. Otherwise
            // We'll just load these on demand.
            ensureAllMeasurements()
            firstRefresh = false
        }
        setCurrentState(currentStartLocation, currentEndLocation, currentTransitionProgress,
                applyImmediately = true)
    }
}

/**
 * An internal key for the cache of mediaViewStates. This is a subset of the full host state.
 */
private data class CacheKey(
    var widthMeasureSpec: Int = -1,
    var heightMeasureSpec: Int = -1,
    var expansion: Float = 0.0f,
    var gutsVisible: Boolean = false
)
