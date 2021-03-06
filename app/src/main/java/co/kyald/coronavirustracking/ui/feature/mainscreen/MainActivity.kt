package co.kyald.coronavirustracking.ui.feature.mainscreen

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import co.kyald.coronavirustracking.BuildConfig
import co.kyald.coronavirustracking.R
import co.kyald.coronavirustracking.data.model.CoronaEntity
import co.kyald.coronavirustracking.ui.feature.preferencescreen.PreferenceActivity
import co.kyald.coronavirustracking.utils.Constants
import co.kyald.coronavirustracking.utils.NotifyWorker
import co.kyald.coronavirustracking.utils.Utils
import co.kyald.coronavirustracking.utils.extensions.gone
import co.kyald.coronavirustracking.utils.extensions.setSafeOnClickListener
import co.kyald.coronavirustracking.utils.extensions.toSimpleString
import co.kyald.coronavirustracking.utils.extensions.visible
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression.get
import com.mapbox.mapboxsdk.style.expressions.Expression.toString
import com.mapbox.mapboxsdk.style.layers.CircleLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.*
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.layers.TransitionOptions
import com.mapbox.mapboxsdk.style.sources.GeoJsonOptions
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.mapboxsdk.utils.BitmapUtils
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.anko.startActivity
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.net.URISyntaxException
import java.util.*
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {

    private val viewModel: MainActivityViewModel by viewModel()

    private val preferences: SharedPreferences by inject()

    private var mapboxMap: MapboxMap? = null

    private var isShown: Boolean = false

    private lateinit var adapterMain: MainRecyclerViewAdapter

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Mapbox.getInstance(this, getString(R.string.access_token))

        setContentView(R.layout.activity_main)

        viewModel.access_token = getString(R.string.access_token)

        viewModel.fetchcoronaData(Dispatchers.IO, false)

        viewModel.coronaLiveData.observe(this, Observer { data ->
            if (data != null) {
                loadEntry(data.feed.entry)
            }
        })

        initMap(savedInstanceState)
        initListener()
        setupAdapter()

    }

    private fun initListener() {
        cardCountryData.setOnClickListener {

            if (!isShown) {
                rvCountry.visible()
                llFields.visible()
                txtShowhide.text = getString(R.string.hide_data)
            } else {
                rvCountry.gone()
                llFields.gone()
                txtShowhide.text = getString(R.string.show_data)
            }

            isShown = !isShown
        }

        btnRefresh.setSafeOnClickListener {
            if (viewModel.coronaDataIsFinished.value?.get("done") == true) {
                viewModel.fetchcoronaData(Dispatchers.IO, true)
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.refresh_timeout),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        fabButton.addOnMenuItemClickListener { fab, textView, itemId ->
            // do something

            when (itemId) {
                R.id.lang -> {
                    val mIntent = Intent(Settings.ACTION_LOCALE_SETTINGS)
                    startActivity(mIntent)
                }

                R.id.whatis -> {
                    val openURL = Intent(Intent.ACTION_VIEW)
                    openURL.data =
                        Uri.parse(getString(R.string.coronavirus_definition))
                    startActivity(openURL)
                }

                R.id.prevent -> {
                    val openURL = Intent(Intent.ACTION_VIEW)
                    openURL.data =
                        Uri.parse(getString(R.string.coronavirus_prevention))
                    startActivity(openURL)
                }

                R.id.pref -> {

                    startActivity<PreferenceActivity>()
                }

                R.id.about -> {
                    Utils().aboutAlert(
                        this, getString(R.string.about),
                        getString(R.string.about_message)
                    )
                    WorkManager.getInstance().cancelAllWork()

                }

            }
        }
    }

    private fun initMap(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapboxMap = map
            map.uiSettings.isCompassEnabled = false
            map.setStyle(Style.LIGHT) { style ->
                // Disable any type of fading transition when icons collide on the map. This enhances the visual
                // look of the data clustering together and breaking apart.
                style.transition = TransitionOptions(0, 0, false)
                mapboxMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(
                            39.913818,
                            116.363625
                        ), 1.0
                    )
                )
                addClusteredGeoJsonSource(style)
                style.addImage(
                    "cross-icon-id",
                    BitmapUtils.getBitmapFromDrawable(resources.getDrawable(R.mipmap.ic_launcher))!!,
                    true
                )
            }
        }
    }

    private fun setupAdapter() {
        adapterMain =
            MainRecyclerViewAdapter(this)

        rvCountry.adapter = adapterMain
        rvCountry.layoutManager = LinearLayoutManager(this)
        rvCountry.setHasFixedSize(true)
    }


    private fun loadEntry(coronaEntity: List<CoronaEntity.Entry>) {
        adapterMain.setEntity(coronaEntity)
    }

    private fun addClusteredGeoJsonSource(loadedMapStyle: Style) { // Add a new source from the GeoJSON data and set the 'cluster' option to true.
        try {

            viewModel.coronaDataIsFinished.observe(this, Observer { data ->
                if (data["done"] == true) {

                    if (data["internet"] == false) {
                        Toast.makeText(
                            this,
                            getString(R.string.network_problem),
                            Toast.LENGTH_SHORT
                        ).show()

                        tvLastUpdate.text =
                            preferences.getString(Constants.PREF_LAST_UPDATE, "")

                    } else {

                        preferences.edit().putString(
                            Constants.PREF_LAST_UPDATE,
                            getString(R.string.last_update) + Date(System.currentTimeMillis()).toSimpleString()
                        ).apply()

                        tvLastUpdate.text =
                            preferences.getString(Constants.PREF_LAST_UPDATE, "")
                    }

                    GlobalScope.launch {

                        val featuresData =
                            withContext(Dispatchers.Default) { viewModel.buildData() }

                        withContext(Dispatchers.Main) {


                            val geoJsonSource =
                                loadedMapStyle.getSourceAs<GeoJsonSource>("coronaVirus")

                            if (geoJsonSource != null) {

                                geoJsonSource.setGeoJson(FeatureCollection.fromFeatures(featuresData))

                            } else {
                                loadedMapStyle.addSource(
                                    GeoJsonSource(
                                        "coronaVirus",
                                        FeatureCollection.fromFeatures(featuresData),
                                        GeoJsonOptions()
                                            .withCluster(true)
                                            .withClusterMaxZoom(100)
                                            .withClusterRadius(20)
                                    )
                                )
                            }

                        }
                    }

                }
            })


        } catch (uriSyntaxException: URISyntaxException) {
            Timber.e("Check the URL %s", uriSyntaxException.message)
        }

        // Use the coronaVirus GeoJSON source to create three layers: One layer for each cluster category.
        // Each point range gets a different fill color.
        val layers = arrayOf(
            intArrayOf(150, ContextCompat.getColor(this, R.color.colorPrimary)),
            intArrayOf(20, ContextCompat.getColor(this, R.color.colorAccent)),
            intArrayOf(0, ContextCompat.getColor(this, R.color.mapbox_blue))
        )
        for (i in layers.indices) { //Add clusters' circles
            val circles = CircleLayer("cluster-$i", "coronaVirus")
            circles.setProperties(
                circleColor(layers[i][1]),
                circleRadius(18f)
            )
            loadedMapStyle.addLayer(circles)
        }

        //Add the count labels
        val count = SymbolLayer("count", "coronaVirus")
        count.setProperties(
            textField(toString(get("point_count"))),
            textSize(12f),
            textColor(Color.WHITE),
            textIgnorePlacement(true),
            textAllowOverlap(true)
        )
        loadedMapStyle.addLayer(count)
    }


    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }


    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }


    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }


    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }


    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }


    override fun onSaveInstanceState(
        outState: Bundle,
        outPersistentState: PersistableBundle
    ) {
        super.onSaveInstanceState(outState, outPersistentState)
        mapView.onSaveInstanceState(outState)
    }

}