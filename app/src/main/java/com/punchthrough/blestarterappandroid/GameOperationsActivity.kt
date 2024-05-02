/*
 * Copyright 2019 Punch Through Design LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.punchthrough.blestarterappandroid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import kotlinx.android.synthetic.main.activity_ble_operations.log_scroll_view
import kotlinx.android.synthetic.main.activity_ble_operations.log_text_view
import org.jetbrains.anko.alert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import kotlin.math.abs

class GameOperationsActivity : AppCompatActivity() {

    object directions { // Should match values in AVR code
        const val UP = "1"
        const val DOWN = "2"
        const val LEFT = "3"
        const val RIGHT = "4"
        const val RESET = "5"
    }

    private lateinit var device: BluetoothDevice
    private lateinit var gestureDetector: GestureDetector
    private lateinit var headerTextView: TextView
    private lateinit var lastSwipeDirectionTextView: TextView
    private var lastSwipeDirection: String = ""
    private lateinit var directionCharacteristic: BluetoothGattCharacteristic

    private val dateFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    private val characteristics by lazy {
        ConnectionManager.servicesOnDevice(device)?.flatMap { service ->
            service.characteristics ?: listOf()
        } ?: listOf()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_operations)
        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice from MainActivity!")

        directionCharacteristic = characteristics.last() // We only care about 1 characteristic to write, it will always be the last one in this list

        headerTextView = findViewById(R.id.header_text_view)
        headerTextView.text = buildString {
            append("2048 Controller")
        }

        val redButton = findViewById<Button>(R.id.reset_button)
        redButton.setOnClickListener {
            performAction("Reset") // Will send reset command when pressed
        }

        lastSwipeDirectionTextView = findViewById(R.id.last_swipe_direction_text_view)
        lastSwipeDirectionTextView.text = ""

        gestureDetector = GestureDetector(this, GestureListener())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Want to check if this touch is a swipe
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        // Some commonly used values from what I could find
        private val swipeThreshold = 100
        private val swipeVelocityThreshold = 100

        // This gets called when there is a swipe
        override fun onFling(
            e1: MotionEvent,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y
            return if (abs(diffX) > abs(diffY)) { // More swiping horizontally rather than vertically, will be either left or right if it was valid
                if (abs(diffX) > swipeThreshold && abs(velocityX) > swipeVelocityThreshold) {
                    if (diffX > 0) {
                        // Right swipe action
                        performAction("Right")
                    } else {
                        // Left swipe action
                        performAction("Left")
                    }
                    true
                } else {
                    super.onFling(e1, e2, velocityX, velocityY)
                }
            } else { // More vertical, should be up or down
                if (abs(diffY) > swipeThreshold && abs(velocityY) > swipeVelocityThreshold) {
                    if (diffY > 0) {
                        // Down swipe action
                        performAction("Down")
                    } else {
                        // Up swipe action
                        performAction("Up")
                    }
                    true
                } else {
                    super.onFling(e1, e2, velocityX, velocityY)
                }
            }
        }
    }

    // Sends the direction to the controller to make a move
    private fun performAction(dir: String) {
        lastSwipeDirection = dir
        lastSwipeDirectionTextView.text = buildString {
            append("Last Swipe Direction: ")
            append(dir)
        }
        val bytes = when (dir) { // I guess this is Kotlin's version of a switch statement
            "Up" -> {
                directions.UP.hexToBytes() // Should be "01"
            }
            "Down" -> {
                directions.DOWN.hexToBytes()
            }
            "Left" -> {
                directions.LEFT.hexToBytes()
            }
            "Right" -> {
                directions.RIGHT.hexToBytes()
            }
            "Reset" -> {
                directions.RESET.hexToBytes()
            }
            else -> {
                "00".hexToBytes() // Shouldn't ever happen
            }
        }

        ConnectionManager.writeCharacteristic(device, directionCharacteristic, bytes)
    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        ConnectionManager.teardownConnection(device)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("SetTextI18n")
    private fun log(message: String) {
        val formattedMessage = String.format("%s: %s", dateFormatter.format(Date()), message)
        runOnUiThread {
            val currentLogText = if (log_text_view.text.isEmpty()) {
                "Beginning of log."
            } else {
                log_text_view.text
            }
            log_text_view.text = "$currentLogText\n$formattedMessage"
            log_scroll_view.post { log_scroll_view.fullScroll(View.FOCUS_DOWN) }
        }
    }


    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    alert {
                        title = "Disconnected"
                        message = "Disconnected from device."
                        positiveButton("OK") { onBackPressed() }
                    }.show()
                }
            }

            onCharacteristicWrite = { _, characteristic ->
                log("Wrote to ${characteristic.uuid}")
            }
        }
    }

    private fun String.hexToBytes() =
        this.chunked(2).map { it.toUpperCase(Locale.US).toInt(16).toByte() }.toByteArray()
}
