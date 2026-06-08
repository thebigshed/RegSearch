package com.example.reg_search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class Vehicle(
    val registrationNumber: String,
    val make: String,
    val colour: String,
    val rawData: JSONObject
)

class MainActivity : AppCompatActivity() {

    private val url = "https://driver-vehicle-licensing.api.gov.uk/vehicle-enquiry/v1/vehicles"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private lateinit var patternInput: EditText
    private lateinit var cameraButton: ImageButton
    private lateinit var makeSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var errorButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var statusText: TextView

    private lateinit var detailOverlay: View
    private lateinit var detailReg: TextView
    private lateinit var detailAllInfo: TextView
    private lateinit var closeDetailButton: Button
    private lateinit var closeDetailX: ImageButton

    private lateinit var errorOverlay: View
    private lateinit var errorLogText: TextView
    private lateinit var closeErrorButton: Button
    private lateinit var closeErrorX: ImageButton

    private val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val digits = "0123456789"
    private val allChars = letters + digits
    private val standardLetters = "ABCDEFGHJKLMNOPRSTUVWXY" 

    private val allResults = mutableListOf<Vehicle>()
    private val errorLog = StringBuilder()
    private lateinit var vehicleAdapter: VehicleAdapter
    
    private var currentExecutor: ExecutorService? = null
    @Volatile
    private var isSearching = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            captureImage()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val imageBitmap = result.data?.extras?.get("data") as? Bitmap
            imageBitmap?.let { processImage(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        patternInput = findViewById(R.id.patternInput)
        cameraButton = findViewById(R.id.cameraButton)
        makeSpinner = findViewById(R.id.makeSpinner)
        colorSpinner = findViewById(R.id.colorSpinner)
        errorButton = findViewById(R.id.errorButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerView)
        statusText = findViewById(R.id.statusText)

        detailOverlay = findViewById(R.id.detailOverlay)
        detailReg = findViewById(R.id.detailReg)
        detailAllInfo = findViewById(R.id.detailAllInfo)
        closeDetailButton = findViewById(R.id.closeDetailButton)
        closeDetailX = findViewById(R.id.closeDetailX)

        errorOverlay = findViewById(R.id.errorOverlay)
        errorLogText = findViewById(R.id.errorLogText)
        closeErrorButton = findViewById(R.id.closeErrorButton)
        closeErrorX = findViewById(R.id.closeErrorX)

        vehicleAdapter = VehicleAdapter(emptyList()) { vehicle ->
            showVehicleDetail(vehicle)
        }
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = vehicleAdapter

        setupSpinners(emptyList(), emptyList())

        cameraButton.setOnClickListener {
            checkCameraPermission()
        }

        startButton.setOnClickListener {
            val pattern = patternInput.text.toString().uppercase().replace(" ", "")

            if (pattern.isEmpty()) {
                Toast.makeText(this, "Enter a pattern", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startSearch(pattern)
        }
        
        stopButton.setOnClickListener {
            stopSearch("Stopped by user")
        }
        
        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateUI()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        makeSpinner.onItemSelectedListener = spinnerListener
        colorSpinner.onItemSelectedListener = spinnerListener

        val dismissDetail = View.OnClickListener { detailOverlay.visibility = View.GONE }
        closeDetailButton.setOnClickListener(dismissDetail)
        closeDetailX.setOnClickListener(dismissDetail)

        errorButton.setOnClickListener {
            errorLogText.text = errorLog.toString()
            errorOverlay.visibility = View.VISIBLE
        }

        val dismissError = View.OnClickListener { errorOverlay.visibility = View.GONE }
        closeErrorButton.setOnClickListener(dismissError)
        closeErrorX.setOnClickListener(dismissError)
    }

    private fun startSearch(pattern: String) {
        isSearching = true
        startButton.visibility = View.GONE
        stopButton.visibility = View.VISIBLE
        
        progressBar.progress = 0
        allResults.clear()
        errorLog.setLength(0)
        errorButton.visibility = View.GONE
        vehicleAdapter.updateData(emptyList())
        setupSpinners(emptyList(), emptyList())

        runSearch(pattern)
    }

    private fun stopSearch(reason: String) {
        isSearching = false
        currentExecutor?.shutdownNow()
        currentExecutor = null
        runOnUiThread {
            startButton.visibility = View.VISIBLE
            stopButton.visibility = View.GONE
            statusText.text = reason
            patternInput.text.clear()
            progressBar.progress = 0
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            captureImage()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun captureImage() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        try {
            takePictureLauncher.launch(takePictureIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImage(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val resultText = visionText.text
                val cleanedText = resultText.uppercase().replace(Regex("[^A-Z0-9]"), "")
                if (cleanedText.isNotEmpty()) {
                    patternInput.setText(cleanedText)
                }
            }
    }

    private fun showVehicleDetail(vehicle: Vehicle) {
        detailReg.text = vehicle.registrationNumber
        val fullInfo = StringBuilder()
        val json = vehicle.rawData
        val keys = json.keys().asSequence().toList().sorted()
        for (key in keys) {
            val value = json.get(key)
            fullInfo.append("${key.replaceFirstChar { it.uppercase() }} : $value\n\n")
        }
        detailAllInfo.text = fullInfo.toString()
        detailOverlay.visibility = View.VISIBLE
    }

    private fun setupSpinners(availableMakes: List<String>, availableColors: List<String>) {
        val makes = listOf("ANY") + availableMakes.distinct().sorted()
        val colors = listOf("ANY") + availableColors.distinct().sorted()

        val makeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, makes)
        makeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        makeSpinner.adapter = makeAdapter

        val colorAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, colors)
        colorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        colorSpinner.adapter = colorAdapter
    }

    private fun updateUI() {
        runOnUiThread {
            val selectedMake = makeSpinner.selectedItem?.toString() ?: "ANY"
            val selectedColor = colorSpinner.selectedItem?.toString() ?: "ANY"
            val filtered = allResults.filter {
                (selectedMake == "ANY" || it.make == selectedMake) &&
                (selectedColor == "ANY" || it.colour == selectedColor)
            }
            vehicleAdapter.updateData(filtered)
        }
    }

    private fun generatePlates(pattern: String): List<String> {
        val results = mutableListOf<String>()
        val wildcards = pattern.count { it == '?' }
        if (wildcards > 3) throw IllegalArgumentException("Max 3 wildcards allowed")

        val isCurrentFormat = pattern.length == 7 && 
            (pattern[0] == '?' || pattern[0].isLetter()) &&
            (pattern[1] == '?' || pattern[1].isLetter()) &&
            (pattern[2] == '?' || pattern[2].isDigit()) &&
            (pattern[3] == '?' || pattern[3].isDigit()) &&
            (pattern[4] == '?' || pattern[4].isLetter()) &&
            (pattern[5] == '?' || pattern[5].isLetter()) &&
            (pattern[6] == '?' || pattern[6].isLetter())

        val isPrefixFormat = pattern.length in 5..7 &&
            (pattern[0] == '?' || pattern[0].isLetter()) &&
            (pattern[1] == '?' || pattern[1].isDigit()) &&
            pattern.drop(1).takeWhile { it.isDigit() || it == '?' }.length in 1..3 &&
            pattern.takeLast(3).all { it.isLetter() || it == '?' }

        val suffixDigitsLen = pattern.drop(3).takeWhile { it.isDigit() || it == '?' }.length
        val isSuffixFormat = pattern.length in 5..7 &&
            pattern.take(3).all { it.isLetter() || it == '?' } &&
            suffixDigitsLen in 1..3 &&
            3 + suffixDigitsLen == pattern.length - 1 &&
            (pattern.last() == '?' || pattern.last().isLetter())

        var handled = false
        if (isCurrentFormat) { generateCurrentFormat(pattern.toCharArray(), 0, results); handled = true }
        if (isPrefixFormat)  { generatePrefixFormat(pattern.toCharArray(), 0, results);  handled = true }
        if (isSuffixFormat)  { generateSuffixFormat(pattern.toCharArray(), 0, results);  handled = true }
        if (!handled) generateBruteForce(pattern.toCharArray(), 0, results)
        return results.distinct()
    }

    private fun generateCurrentFormat(current: CharArray, index: Int, results: MutableList<String>) {
        if (index == current.size) { results.add(String(current)); return }
        if (current[index] != '?') { generateCurrentFormat(current, index + 1, results); return }
        val possibleChars = when (index) {
            0, 1 -> standardLetters
            2, 3 -> digits
            4, 5, 6 -> standardLetters
            else -> allChars
        }
        for (c in possibleChars) {
            current[index] = c
            generateCurrentFormat(current, index + 1, results)
            current[index] = '?'
        }
    }

    private fun generatePrefixFormat(current: CharArray, index: Int, results: MutableList<String>) {
        if (index == current.size) { results.add(String(current)); return }
        if (current[index] != '?') { generatePrefixFormat(current, index + 1, results); return }
        val lastThreeStart = current.size - 3
        val possibleChars = when {
            index == 0 -> standardLetters
            index < lastThreeStart -> digits
            else -> standardLetters
        }
        for (c in possibleChars) {
            current[index] = c
            generatePrefixFormat(current, index + 1, results)
            current[index] = '?'
        }
    }

    private fun generateSuffixFormat(current: CharArray, index: Int, results: MutableList<String>) {
        if (index == current.size) { results.add(String(current)); return }
        if (current[index] != '?') { generateSuffixFormat(current, index + 1, results); return }
        val yearSuffixPos = current.size - 1
        val possibleChars = when {
            index < 3 -> standardLetters
            index == yearSuffixPos -> standardLetters
            else -> digits
        }
        for (c in possibleChars) {
            current[index] = c
            generateSuffixFormat(current, index + 1, results)
            current[index] = '?'
        }
    }

    private fun generateBruteForce(current: CharArray, index: Int, results: MutableList<String>) {
        if (index == current.size) { results.add(String(current)); return }
        if (current[index] != '?') { generateBruteForce(current, index + 1, results); return }
        for (c in allChars) {
            current[index] = c
            generateBruteForce(current, index + 1, results)
            current[index] = '?'
        }
    }

    private fun runSearch(pattern: String) {
        val plates = try {
            generatePlates(pattern)
        } catch (e: Exception) {
            stopSearch(e.message ?: "Error")
            return
        }
        
        val total = plates.size
        progressBar.max = total
        statusText.text = "0 / $total"

        val count = AtomicInteger(0)
        val threadCount = 2
        val executor = Executors.newFixedThreadPool(threadCount)
        currentExecutor = executor

        for (plate in plates) {
            executor.execute {
                try {
                    if (!isSearching) return@execute
                    
                    Thread.sleep(200) 
                    
                    if (!isSearching) return@execute

                    val json = JSONObject()
                    json.put("registrationNumber", plate)
                    val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val request = Request.Builder().url(url).post(body).addHeader("x-api-key", "ZfiU74NOrp8VWOnfGtP1232VdprwnX4h66A3MVJO").build()

                    val response = client.newCall(request).execute()
                    response.use {
                        if (!isSearching) return@use
                        if (response.code == 200) {
                            val responseBody = response.body.string()
                            val parsed = JSONObject(responseBody)
                            val make = parsed.optString("make", "").uppercase()
                            val color = parsed.optString("colour", "").uppercase()
                            val reg = parsed.optString("registrationNumber", plate).uppercase()
                            synchronized(allResults) { allResults.add(Vehicle(reg, make, color, parsed)) }
                            updateUI()
                        } else {
                            synchronized(errorLog) { errorLog.append("Plate $plate: HTTP ${response.code}\n") }
                            runOnUiThread { if (isSearching) errorButton.visibility = View.VISIBLE }
                            if (response.code == 429) {
                                Thread.sleep(1000)
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    // Normal when shutdownNow() is called
                } catch (e: Exception) {
                    if (isSearching) {
                        synchronized(errorLog) { errorLog.append("Plate $plate: ${e.message}\n") }
                        runOnUiThread { errorButton.visibility = View.VISIBLE }
                    }
                } finally {
                    val currentCount = count.incrementAndGet()
                    runOnUiThread {
                        if (isSearching) {
                            progressBar.progress = currentCount
                            statusText.text = "$currentCount / $total"
                            if (currentCount == total) {
                                finalizeSearch(total)
                            }
                        }
                    }
                }
            }
        }
        executor.shutdown()
    }

    private fun finalizeSearch(total: Int) {
        isSearching = false
        runOnUiThread {
            startButton.visibility = View.VISIBLE
            stopButton.visibility = View.GONE
            var finalStatus = "Done ($total checked)"
            if (allResults.isEmpty()) finalStatus += " - Not Found"
            statusText.text = finalStatus
            val foundMakes = allResults.map { it.make }.filter { it.isNotEmpty() }
            val foundColors = allResults.map { it.colour }.filter { it.isNotEmpty() }
            setupSpinners(foundMakes, foundColors)
        }
    }
}
