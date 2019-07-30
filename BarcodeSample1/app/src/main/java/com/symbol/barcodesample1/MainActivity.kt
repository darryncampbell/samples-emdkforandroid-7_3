/*
 * Copyright (C) 2015-2019 Zebra Technologies Corporation and/or its affiliates
 * All rights reserved.
 */
package com.symbol.barcodesample1

import java.util.ArrayList

import com.symbol.emdk.EMDKManager
import com.symbol.emdk.EMDKResults
import com.symbol.emdk.EMDKManager.EMDKListener
import com.symbol.emdk.EMDKManager.FEATURE_TYPE
import com.symbol.emdk.barcode.BarcodeManager
import com.symbol.emdk.barcode.BarcodeManager.ConnectionState
import com.symbol.emdk.barcode.BarcodeManager.ScannerConnectionListener
import com.symbol.emdk.barcode.ScanDataCollection
import com.symbol.emdk.barcode.Scanner
import com.symbol.emdk.barcode.ScannerConfig
import com.symbol.emdk.barcode.ScannerException
import com.symbol.emdk.barcode.ScannerInfo
import com.symbol.emdk.barcode.ScannerResults
import com.symbol.emdk.barcode.ScanDataCollection.ScanData
import com.symbol.emdk.barcode.Scanner.DataListener
import com.symbol.emdk.barcode.Scanner.StatusListener
import com.symbol.emdk.barcode.Scanner.TriggerType
import com.symbol.emdk.barcode.StatusData.ScannerStates
import com.symbol.emdk.barcode.StatusData

import android.app.Activity
import android.os.Bundle
import android.text.Html
import android.text.method.ScrollingMovementMethod
import android.util.DisplayMetrics
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView.OnItemSelectedListener
import android.content.pm.ActivityInfo

class MainActivity : Activity(), EMDKListener, DataListener, StatusListener, ScannerConnectionListener, OnCheckedChangeListener {

    private var emdkManager: EMDKManager? = null
    private var barcodeManager: BarcodeManager? = null
    private var scanner: Scanner? = null

    private var textViewData: TextView? = null
    private var textViewStatus: TextView? = null

    private var checkBoxEAN8: CheckBox? = null
    private var checkBoxEAN13: CheckBox? = null
    private var checkBoxCode39: CheckBox? = null
    private var checkBoxCode128: CheckBox? = null

    private var spinnerScannerDevices: Spinner? = null

    private var deviceList: List<ScannerInfo>? = null

    private var scannerIndex = 0 // Keep the selected scanner
    private var defaultIndex = 0 // Keep the default scanner
    private var dataLength = 0
    private var statusString = ""

    private var bSoftTriggerSelected = false
    private var bDecoderSettingsChanged = false
    private var bExtScannerDisconnected = false
    private val lock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceList = ArrayList()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        setDefaultOrientation()

        textViewData = findViewById<View>(R.id.textViewData) as TextView
        textViewStatus = findViewById<View>(R.id.textViewStatus) as TextView
        checkBoxEAN8 = findViewById<View>(R.id.checkBoxEAN8) as CheckBox
        checkBoxEAN13 = findViewById<View>(R.id.checkBoxEAN13) as CheckBox
        checkBoxCode39 = findViewById<View>(R.id.checkBoxCode39) as CheckBox
        checkBoxCode128 = findViewById<View>(R.id.checkBoxCode128) as CheckBox
        spinnerScannerDevices = findViewById<View>(R.id.spinnerScannerDevices) as Spinner

        val results = EMDKManager.getEMDKManager(applicationContext, this)
        if (results.statusCode != EMDKResults.STATUS_CODE.SUCCESS) {
            updateStatus("EMDKManager object request failed!")
            return
        }

        checkBoxEAN8!!.setOnCheckedChangeListener(this)
        checkBoxEAN13!!.setOnCheckedChangeListener(this)
        checkBoxCode39!!.setOnCheckedChangeListener(this)
        checkBoxCode128!!.setOnCheckedChangeListener(this)

        addSpinnerScannerDevicesListener()

        textViewData!!.isSelected = true
        textViewData!!.movementMethod = ScrollingMovementMethod()
    }

    override fun onOpened(emdkManager: EMDKManager) {
        updateStatus("EMDK open success!")
        this.emdkManager = emdkManager
        // Acquire the barcode manager resources
        initBarcodeManager()
        // Enumerate scanner devices
        enumerateScannerDevices()
        // Set default scanner
        spinnerScannerDevices!!.setSelection(defaultIndex)
    }

    override fun onResume() {
        super.onResume()
        // The application is in foreground
        if (emdkManager != null) {
            // Acquire the barcode manager resources
            initBarcodeManager()
            // Enumerate scanner devices
            enumerateScannerDevices()
            // Set selected scanner
            spinnerScannerDevices!!.setSelection(scannerIndex)
            // Initialize scanner
            initScanner()
        }
    }

    override fun onPause() {
        super.onPause()
        // The application is in background
        // Release the barcode manager resources
        deInitScanner()
        deInitBarcodeManager()
    }

    override fun onClosed() {
        // Release all the resources
        if (emdkManager != null) {
            emdkManager!!.release()
            emdkManager = null
        }
        updateStatus("EMDK closed unexpectedly! Please close and restart the application.")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release all the resources
        if (emdkManager != null) {
            emdkManager!!.release()
            emdkManager = null
        }
    }

    override fun onData(scanDataCollection: ScanDataCollection?) {
        if (scanDataCollection != null && scanDataCollection.result == ScannerResults.SUCCESS) {
            val scanData = scanDataCollection.scanData
            for (data in scanData) {
                updateData("<font color='gray'>" + data.labelType + "</font> : " + data.data)
            }
        }
    }

    override fun onStatus(statusData: StatusData) {
        val state = statusData.state
        when (state) {
            StatusData.ScannerStates.IDLE -> {
                statusString = statusData.friendlyName + " is enabled and idle..."
                updateStatus(statusString)
                // set trigger type
                if (bSoftTriggerSelected) {
                    scanner!!.triggerType = TriggerType.SOFT_ONCE
                    bSoftTriggerSelected = false
                } else {
                    scanner!!.triggerType = TriggerType.HARD
                }
                // set decoders
                if (bDecoderSettingsChanged) {
                    setDecoders()
                    bDecoderSettingsChanged = false
                }
                // submit read
                if (!scanner!!.isReadPending && !bExtScannerDisconnected) {
                    try {
                        scanner!!.read()
                    } catch (e: ScannerException) {
                        updateStatus(e.message.toString())
                    }

                }
            }
            StatusData.ScannerStates.WAITING -> {
                statusString = "Scanner is waiting for trigger press..."
                updateStatus(statusString)
            }
            StatusData.ScannerStates.SCANNING -> {
                statusString = "Scanning..."
                updateStatus(statusString)
            }
            StatusData.ScannerStates.DISABLED -> {
                statusString = statusData.friendlyName + " is disabled."
                updateStatus(statusString)
            }
            StatusData.ScannerStates.ERROR -> {
                statusString = "An error has occurred."
                updateStatus(statusString)
            }
            else -> {
            }
        }
    }

    override fun onConnectionChange(scannerInfo: ScannerInfo, connectionState: ConnectionState) {
        val status: String
        var scannerName = ""
        val statusExtScanner = connectionState.toString()
        val scannerNameExtScanner = scannerInfo.friendlyName
        if (deviceList!!.size != 0) {
            scannerName = deviceList!![scannerIndex].friendlyName
        }
        if (scannerName.equals(scannerNameExtScanner, ignoreCase = true)) {
            when (connectionState) {
                BarcodeManager.ConnectionState.CONNECTED -> {
                    bSoftTriggerSelected = false
                    synchronized(lock) {
                        initScanner()
                        bExtScannerDisconnected = false
                    }
                }
                BarcodeManager.ConnectionState.DISCONNECTED -> {
                    bExtScannerDisconnected = true
                    synchronized(lock) {
                        deInitScanner()
                    }
                }
            }
            status = "$scannerNameExtScanner:$statusExtScanner"
            updateStatus(status)
        } else {
            bExtScannerDisconnected = false
            status = "$statusString $scannerNameExtScanner:$statusExtScanner"
            updateStatus(status)
        }
    }

    private fun initScanner() {
        if (scanner == null) {
            if (deviceList != null && deviceList!!.size != 0) {
                if (barcodeManager != null)
                    scanner = barcodeManager!!.getDevice(deviceList!![scannerIndex])
            } else {
                updateStatus("Failed to get the specified scanner device! Please close and restart the application.")
                return
            }
            if (scanner != null) {
                scanner!!.addDataListener(this)
                scanner!!.addStatusListener(this)
                try {
                    scanner!!.enable()
                } catch (e: ScannerException) {
                    updateStatus(e.message.toString())
                    deInitScanner()
                }

            } else {
                updateStatus("Failed to initialize the scanner device.")
            }
        }
    }

    private fun deInitScanner() {
        if (scanner != null) {
            try {
                scanner!!.disable()
            } catch (e: Exception) {
                updateStatus(e.message.toString())
            }

            try {
                scanner!!.removeDataListener(this)
                scanner!!.removeStatusListener(this)
            } catch (e: Exception) {
                updateStatus(e.message.toString())
            }

            try {
                scanner!!.release()
            } catch (e: Exception) {
                updateStatus(e.message.toString())
            }

            scanner = null
        }
    }

    private fun initBarcodeManager() {
        barcodeManager = emdkManager!!.getInstance(FEATURE_TYPE.BARCODE) as BarcodeManager
        // Add connection listener
        if (barcodeManager != null) {
            barcodeManager!!.addConnectionListener(this)
        }
    }

    private fun deInitBarcodeManager() {
        if (emdkManager != null) {
            emdkManager!!.release(FEATURE_TYPE.BARCODE)
        }
    }

    private fun addSpinnerScannerDevicesListener() {
        spinnerScannerDevices!!.onItemSelectedListener = object : OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, arg1: View, position: Int, arg3: Long) {
                if (scannerIndex != position || scanner == null) {
                    scannerIndex = position
                    bSoftTriggerSelected = false
                    bExtScannerDisconnected = false
                    deInitScanner()
                    initScanner()
                }
            }

            override fun onNothingSelected(arg0: AdapterView<*>) {}
        }
    }

    private fun enumerateScannerDevices() {
        if (barcodeManager != null) {
            val friendlyNameList = ArrayList<String>()
            var spinnerIndex = 0
            deviceList = barcodeManager!!.supportedDevicesInfo
            if (deviceList != null && deviceList!!.size != 0) {
                val it = deviceList!!.iterator()
                while (it.hasNext()) {
                    val scnInfo = it.next()
                    friendlyNameList.add(scnInfo.friendlyName)
                    if (scnInfo.isDefaultScanner) {
                        defaultIndex = spinnerIndex
                    }
                    ++spinnerIndex
                }
            } else {
                updateStatus("Failed to get the list of supported scanner devices! Please close and restart the application.")
            }
            val spinnerAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, friendlyNameList)
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerScannerDevices!!.adapter = spinnerAdapter
        }
    }

    private fun setDecoders() {
        if (scanner != null) {
            try {
                val config = scanner!!.config
                // Set EAN8
                config.decoderParams.ean8.enabled = checkBoxEAN8!!.isChecked
                // Set EAN13
                config.decoderParams.ean13.enabled = checkBoxEAN13!!.isChecked
                // Set Code39
                config.decoderParams.code39.enabled = checkBoxCode39!!.isChecked
                //Set Code128
                config.decoderParams.code128.enabled = checkBoxCode128!!.isChecked
                scanner!!.config = config
            } catch (e: ScannerException) {
                updateStatus(e.message.toString())
            }

        }
    }

    fun softScan(view: View) {
        bSoftTriggerSelected = true
        cancelRead()
    }

    private fun cancelRead() {
        if (scanner != null) {
            if (scanner!!.isReadPending) {
                try {
                    scanner!!.cancelRead()
                } catch (e: ScannerException) {
                    updateStatus(e.message.toString())
                }

            }
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread { textViewStatus!!.text = "" + status }
    }

    private fun updateData(result: String?) {
        runOnUiThread {
            if (result != null) {
                if (dataLength++ > 100) { //Clear the cache after 100 scans
                    textViewData!!.text = ""
                    dataLength = 0
                }
                textViewData!!.append(Html.fromHtml(result))
                textViewData!!.append("\n")
                (findViewById(R.id.scrollViewData) as View).post { (findViewById<View>(R.id.scrollViewData) as ScrollView).fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun setDefaultOrientation() {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        val width = dm.widthPixels
        val height = dm.heightPixels
        if (width > height) {
            setContentView(R.layout.activity_main_landscape)
        } else {
            setContentView(R.layout.activity_main)
        }
    }

    override fun onCheckedChanged(arg0: CompoundButton, arg1: Boolean) {
        bDecoderSettingsChanged = true
        cancelRead()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        return if (id == R.id.action_settings) {
            true
        } else super.onOptionsItemSelected(item)
    }
}
