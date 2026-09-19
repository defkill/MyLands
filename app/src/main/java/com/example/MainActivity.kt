package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.example.ui.screen.NavigationMainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()

  override fun onResume() {
    super.onResume()
    // The user may have toggled GPS (or granted the permission) while the app was in the
    // background; re-check on every return so the map is not stuck without a position.
    val hasPermission = ContextCompat.checkSelfPermission(
      this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_COARSE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED

    if (hasPermission) {
      viewModel.locationTracker.syncProviders()
    }
  }

  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
      viewModel.onLowMemory()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        val permissionLauncher = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
          val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
          if (granted) {
            viewModel.locationTracker.startListening()
          }
        }

        val activityRecognitionLauncher = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
          if (granted) {
            // Re-register the step sensor: the earlier registration produced no events.
            viewModel.stepDetectorManager.restart()
          }
        }

        LaunchedEffect(Unit) {
          val hasFine = ContextCompat.checkSelfPermission(
            this@MainActivity,
            Manifest.permission.ACCESS_FINE_LOCATION
          ) == PackageManager.PERMISSION_GRANTED
          val hasCoarse = ContextCompat.checkSelfPermission(
            this@MainActivity,
            Manifest.permission.ACCESS_COARSE_LOCATION
          ) == PackageManager.PERMISSION_GRANTED

          // Step sensors deliver nothing without ACTIVITY_RECOGNITION on Android 10+.
          // It is declared in the manifest but is a runtime permission, and nobody was asking
          // for it — so registerListener succeeded silently and zero step events ever arrived,
          // which is why dead reckoning produced no track at all.
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
              this@MainActivity,
              Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
          ) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
          }

          if (hasFine || hasCoarse) {
            // Permission was already granted on a previous run: start immediately.
            // Without this branch GPS only ever started right after the permission
            // dialog, so on every later launch the user was never located.
            viewModel.locationTracker.startListening()
          } else {
            permissionLauncher.launch(
              arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
              )
            )
          }
        }

        NavigationMainScreen(viewModel = viewModel)
      }
    }
  }
}

