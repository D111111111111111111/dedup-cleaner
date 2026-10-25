package com.primusobscurus.dedupcleaner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private lateinit var deleteButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var radioGroup: RadioGroup
    private lateinit var list: RecyclerView

    private val adapter = GroupAdapter { updateDeleteButton() }
    private val scope = CoroutineScope(Dispatchers.Main)

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (grants.values.any { it }) {
                startScan()
            } else {
                Toast.makeText(
                    this,
                    R.string.perm_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val deleteLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) {
            Toast.makeText(
                this,
                R.string.delete_done,
                Toast.LENGTH_LONG
            ).show()
            startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        scanButton = findViewById(R.id.scanButton)
        deleteButton = findViewById(R.id.deleteButton)
        progress = findViewById(R.id.progress)
        radioGroup = findViewById(R.id.thresholdGroup)
        list = findViewById(R.id.recycler)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        scanButton.setOnClickListener {
            checkPermissionsThenScan()
        }

        deleteButton.setOnClickListener {
            deleteSelected()
        }

        updateDeleteButton()
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissionsThenScan() {
        if (hasPermissions()) {
            startScan()
        } else {
            permissionLauncher.launch(
                if (Build.VERSION.SDK_INT >= 33) {
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    )
                } else {
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    )
                }
            )
        }
    }

    private fun threshold(): Int =
        when (radioGroup.checkedRadioButtonId) {
            R.id.radio70 -> 70
            R.id.radio90 -> 90
            else -> 80
        }

    private fun startScan() {
        scanButton.isEnabled = false
        progress.visibility = View.VISIBLE

        scope.launch {
            val result = DuplicateScanner.scan(
                this@MainActivity,
                threshold()
            ) { msg ->
                status.text = msg
            }

            // Keep the most recent file in each exact-duplicate group.
            // Preselect the other files for deletion.
            for (group in result.exactGroups) {
                val keep = group.items.maxByOrNull { it.date }
                group.items.forEach { item ->
                    item.selectedForDeletion = item != keep
                }
            }

            val groups = result.exactGroups + result.similarGroups

            loadThumbnails(groups)
            adapter.groups = groups

            progress.visibility = View.GONE
            scanButton.isEnabled = true

            status.text = getString(
                R.string.scan_done,
                result.exactGroups.size,
                result.similarGroups.size
            )

            updateDeleteButton()
        }
    }

    private fun loadThumbnails(groups: List<DuplicateGroup>) {
        val size = 256

        for (group in groups) {
            for (item in group.items) {
                try {
                    item.thumb = contentResolver.loadThumbnail(
                        item.uri,
                        android.util.Size(size, size),
                        null
                    )
                } catch (e: Exception) {
                    item.thumb = Hashing.decodeForHash(
                        File(item.path)
                    )
                }
            }
        }
    }

    private fun updateDeleteButton() {
        val count = adapter.groups.sumOf { group ->
            group.items.count { it.selectedForDeletion }
        }

        val bytes = adapter.groups
            .flatMap { it.items }
            .filter { it.selectedForDeletion }
            .sumOf { it.size }

        deleteButton.text = getString(
            R.string.delete_button,
            count,
            bytes / 1048576.0
        )

        deleteButton.isEnabled = count > 0
    }

    private fun deleteSelected() {
        val selected = adapter.groups
            .flatMap { it.items }
            .filter { it.selectedForDeletion }

        if (selected.isEmpty()) return

        if (Build.VERSION.SDK_INT >= 30) {
            val uris = selected.map { it.uri }

            val pending = MediaStore.createDeleteRequest(
                contentResolver,
                uris
            )

            val request = IntentSenderRequest.Builder(
                pending.intentSender
            ).build()

            deleteLauncher.launch(request)

        } else {
            scope.launch {
                withContext(Dispatchers.IO) {
                    for (item in selected) {
                        File(item.path).delete()
                    }
                }

                Toast.makeText(
                    this@MainActivity,
                    R.string.delete_done,
                    Toast.LENGTH_LONG
                ).show()

                startScan()
            }
        }
    }
}
