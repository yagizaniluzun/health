package cachet.plugins.health

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.NonNull
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.MealType.MEAL_TYPE_BREAKFAST
import androidx.health.connect.client.records.MealType.MEAL_TYPE_DINNER
import androidx.health.connect.client.records.MealType.MEAL_TYPE_LUNCH
import androidx.health.connect.client.records.MealType.MEAL_TYPE_SNACK
import androidx.health.connect.client.records.MealType.MEAL_TYPE_UNKNOWN
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.*
import kotlinx.coroutines.*

const val CHANNEL_NAME = "flutter_health"


const val STEPS = "STEPS"


class HealthPlugin(private var channel: MethodChannel? = null) :
    MethodCallHandler, ActivityResultListener, Result, ActivityAware, FlutterPlugin {
    private var mResult: Result? = null
    private var handler: Handler? = null
    private var activity: Activity? = null
    private var context: Context? = null
    private var threadPoolExecutor: ExecutorService? = null
    private var healthConnectRequestPermissionsLauncher: ActivityResultLauncher<Set<String>>? =
        null
    private lateinit var healthConnectClient: HealthConnectClient
    private lateinit var scope: CoroutineScope


    override fun onAttachedToEngine(
        @NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
    ) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL_NAME)
        channel?.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
        threadPoolExecutor = Executors.newFixedThreadPool(4)
        checkAvailability()
        if (healthConnectAvailable) {
            healthConnectClient =
                HealthConnectClient.getOrCreate(
                    flutterPluginBinding.applicationContext
                )
        }

    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = null
        activity = null
        threadPoolExecutor!!.shutdown()
        threadPoolExecutor = null
    }

    override fun success(p0: Any?) {
        handler?.post { mResult?.success(p0) }
    }

    override fun notImplemented() {
        handler?.post { mResult?.notImplemented() }
    }

    override fun error(
        errorCode: String,
        errorMessage: String?,
        errorDetails: Any?,
    ) {
        handler?.post { mResult?.error(errorCode, errorMessage, errorDetails) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return false
    }

    /** Handle calls from the MethodChannel */
    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "installHealthConnect" -> installHealthConnect(call, result)
            "getHealthConnectSdkStatus" -> getHealthConnectSdkStatus(call, result)
            "hasPermissions" -> hasPermissions(call, result)
            "requestAuthorization" -> requestAuthorization(call, result)
            "revokePermissions" -> revokePermissions(call, result)
            "getData" -> getData(call, result)
            "getIntervalData" -> getIntervalData(call, result)
            "writeData" -> writeData(call, result)
            "delete" -> deleteData(call, result)
            "getAggregateData" -> getAggregateData(call, result)
            "getTotalStepsInInterval" -> getTotalStepsInInterval(call, result)
            "writeWorkoutData" -> writeWorkoutData(call, result)
            "writeBloodPressure" -> writeBloodPressure(call, result)
            "writeBloodOxygen" -> writeBloodOxygen(call, result)
            "writeMenstruationFlow" -> writeMenstruationFlow(call, result)
            "writeMeal" -> writeMeal(call, result)
            else -> result.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        if (channel == null) {
            return
        }
        binding.addActivityResultListener(this)
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        if (channel == null) {
            return
        }
        activity = null
        healthConnectRequestPermissionsLauncher = null
    }

    private var healthConnectAvailable = false
    private var healthConnectStatus = HealthConnectClient.SDK_UNAVAILABLE

    private fun checkAvailability() {
        healthConnectStatus = HealthConnectClient.getSdkStatus(context!!)
        healthConnectAvailable = healthConnectStatus == HealthConnectClient.SDK_AVAILABLE
    }

    private fun installHealthConnect(call: MethodCall, result: Result) {
        val uriString =
            "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
        context!!.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.android.vending")
                data = Uri.parse(uriString)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("overlay", true)
                putExtra("callerId", context!!.packageName)
            }
        )
        result.success(null)
    }

    private fun onHealthConnectPermissionCallback(permissionGranted: Set<String>) {
        if (permissionGranted.isEmpty()) {
            mResult?.success(false)
            Log.i(
                "FLUTTER_HEALTH",
                "Health Connect permissions were not granted! Make sure to declare the required permissions in the AndroidManifest.xml file."
            )
        } else {
            mResult?.success(true)
            Log.i(
                "FLUTTER_HEALTH",
                "${permissionGranted.size} Health Connect permissions were granted!"
            )

            // log the permissions granted for debugging
            Log.i("FLUTTER_HEALTH", "Permissions granted: $permissionGranted")
        }
    }

    /** Save a Nutrition measurement with calories, carbs, protein, fat, name and mealType */
    private fun writeMeal(call: MethodCall, result: Result) {
        result.success(false)
    }

    /**
     * Save menstrual flow data
     */
    private fun writeMenstruationFlow(call: MethodCall, result: Result) {
        writeData(call, result)
    }

    /**
     * Save the blood oxygen saturation
     */
    private fun writeBloodOxygen(call: MethodCall, result: Result) {
        writeData(call, result)
    }

    private fun getIntervalData(call: MethodCall, result: Result) {
        getAggregateData(call, result)
    }

    /**
     * Revokes access to Health Connect using `revokeAllPermissions`.
     *
     * Note: When using `revokePermissions` with Health Connect, the app must be completely killed
     * for it to take effect.
     */
    private fun revokePermissions(call: MethodCall, result: Result) {
        scope.launch {
            Log.i("Health", "Disabling Health Connect")
            healthConnectClient.permissionController.revokeAllPermissions()
        }
        result.success(true)
    }

    private fun getTotalStepsInInterval(call: MethodCall, result: Result) {
        val start = call.argument<Long>("startTime")!!
        val end = call.argument<Long>("endTime")!!
        val recordingMethodsToFilter = call.argument<List<Int>>("recordingMethodsToFilter")!!

        if (recordingMethodsToFilter.isEmpty()) {
            getAggregatedStepCount(start, end, result)
        } else {
            getStepCountFiltered(start, end, recordingMethodsToFilter, result)
        }
    }

    private fun getAggregatedStepCount(start: Long, end: Long, result: Result) {
        result.success(null)
    }

    /** get the step records manually and filter out manual entries **/
    private fun getStepCountFiltered(
        start: Long,
        end: Long,
        recordingMethodsToFilter: List<Int>,
        result: Result
    ) {
        scope.launch {
            try {
                val request =
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter =
                            TimeRangeFilter.between(
                                Instant.ofEpochMilli(start),
                                Instant.ofEpochMilli(end)
                            ),
                    )
                val response = healthConnectClient.readRecords(request)
                val filteredRecords = filterRecordsByRecordingMethods(
                    recordingMethodsToFilter,
                    response.records
                )
                val totalSteps = filteredRecords.sumOf { (it as StepsRecord).count.toInt() }
                Log.i(
                    "FLUTTER_HEALTH::SUCCESS",
                    "returning $totalSteps steps (excluding manual entries)"
                )
                result.success(totalSteps)
            } catch (e: Exception) {
                Log.e(
                    "FLUTTER_HEALTH::ERROR",
                    "Unable to return steps due to the following exception:"
                )
                Log.e("FLUTTER_HEALTH::ERROR", Log.getStackTraceString(e))
                result.success(null)
            }
        }
    }

    private fun getHealthConnectSdkStatus(call: MethodCall, result: Result) {
        checkAvailability()
        if (healthConnectAvailable) {
            healthConnectClient =
                HealthConnectClient.getOrCreate(
                    context!!
                )
        }
        result.success(healthConnectStatus)
    }

    /** Filter records by recording methods */
    private fun filterRecordsByRecordingMethods(
        recordingMethodsToFilter: List<Int>,
        records: List<Record>
    ): List<Record> {
        if (recordingMethodsToFilter.isEmpty()) {
            return records
        }

        return records.filter { record ->
            Log.i(
                "FLUTTER_HEALTH",
                "Filtering record with recording method ${record.metadata.recordingMethod}, filtering by $recordingMethodsToFilter. Result: ${
                    recordingMethodsToFilter.contains(
                        record.metadata.recordingMethod
                    )
                }"
            )
            return@filter !recordingMethodsToFilter.contains(record.metadata.recordingMethod)
        }
    }

    private fun hasPermissions(call: MethodCall, result: Result) {
        val args = call.arguments as HashMap<*, *>
        val types = (args["types"] as? ArrayList<*>)?.filterIsInstance<String>()!!
        val permissions = (args["permissions"] as? ArrayList<*>)?.filterIsInstance<Int>()!!

        val permList = mutableListOf<String>()
        for ((i, typeKey) in types.withIndex()) {
            if (!mapToType.containsKey(typeKey)) {
                Log.w(
                    "FLUTTER_HEALTH::ERROR",
                    "Datatype $typeKey not found in HC"
                )
                result.success(false)
                return
            }
            val access = permissions[i]
            val dataType = mapToType[typeKey]!!
            if (access == 0) {
                permList.add(
                    HealthPermission.getReadPermission(dataType),
                )
            } else {
                permList.addAll(
                    listOf(
                        HealthPermission.getReadPermission(
                            dataType
                        ),
                    ),
                )
            }
            // Workout also needs distance and total energy burned too
        }
        scope.launch {
            result.success(
                healthConnectClient
                    .permissionController
                    .getGrantedPermissions()
                    .containsAll(permList),
            )
        }
    }

    /**
     * Requests authorization for the HealthDataTypes with the the READ or READ_WRITE permission
     * type.
     */
    private fun requestAuthorization(call: MethodCall, result: Result) {
        if (context == null) {
            result.success(false)
            return
        }

        val args = call.arguments as HashMap<*, *>
        val types = (args["types"] as? ArrayList<*>)?.filterIsInstance<String>()!!
        val permissions = (args["permissions"] as? ArrayList<*>)?.filterIsInstance<Int>()!!

        val permList = mutableListOf<String>()
        for ((i, typeKey) in types.withIndex()) {
            if (!mapToType.containsKey(typeKey)) {
                Log.w(
                    "FLUTTER_HEALTH::ERROR",
                    "Datatype $typeKey not found in HC"
                )
                result.success(false)
                return
            }
            val access = permissions[i]!!
            val dataType = mapToType[typeKey]!!
            if (access == 0) {
                permList.add(
                    HealthPermission.getReadPermission(dataType),
                )
            } else {
                permList.addAll(
                    listOf(
                        HealthPermission.getReadPermission(
                            dataType
                        ),
                    ),
                )
            }
            // Workout also needs distance and total energy burned too
        }
        if (healthConnectRequestPermissionsLauncher == null) {
            result.success(false)
            Log.i("FLUTTER_HEALTH", "Permission launcher not found")
            return
        }

        // Store the result to be called in [onHealthConnectPermissionCallback]
        mResult = result
        healthConnectRequestPermissionsLauncher!!.launch(permList.toSet())
    }

    /** Get all datapoints of the DataType within the given time range */
    private fun getData(call: MethodCall, result: Result) {
        val dataType = call.argument<String>("dataTypeKey")!!
        val startTime = Instant.ofEpochMilli(call.argument<Long>("startTime")!!)
        val endTime = Instant.ofEpochMilli(call.argument<Long>("endTime")!!)
        val healthConnectData = mutableListOf<Map<String, Any?>>()
        val recordingMethodsToFilter = call.argument<List<Int>>("recordingMethodsToFilter")!!

        Log.i(
            "FLUTTER_HEALTH",
            "Getting data for $dataType between $startTime and $endTime, filtering by $recordingMethodsToFilter"
        )

        scope.launch {
            try {
                mapToType[dataType]?.let { classType ->
                    val records = mutableListOf<Record>()

                    // Set up the initial request to read health records with specified
                    // parameters
                    var request =
                        ReadRecordsRequest(
                            recordType = classType,
                            // Define the maximum amount of data
                            // that HealthConnect can return
                            // in a single request
                            timeRangeFilter =
                                TimeRangeFilter.between(
                                    startTime,
                                    endTime
                                ),
                        )

                    var response = healthConnectClient.readRecords(request)
                    var pageToken = response.pageToken

                    // Add the records from the initial response to the records list
                    records.addAll(response.records)

                    // Continue making requests and fetching records while there is a
                    // page token
                    while (!pageToken.isNullOrEmpty()) {
                        request =
                            ReadRecordsRequest(
                                recordType = classType,
                                timeRangeFilter =
                                    TimeRangeFilter.between(
                                        startTime,
                                        endTime
                                    ),
                                pageToken = pageToken
                            )
                        response = healthConnectClient.readRecords(request)

                        pageToken = response.pageToken
                        records.addAll(response.records)
                    }
                    val filteredRecords = filterRecordsByRecordingMethods(
                        recordingMethodsToFilter,
                        records
                    )
                    for (rec in filteredRecords) {
                        healthConnectData.addAll(
                            convertRecord(rec, dataType)
                        )
                    }
                }
                Handler(context!!.mainLooper).run { result.success(healthConnectData) }
            } catch (e: Exception) {
                Log.i(
                    "FLUTTER_HEALTH::ERROR",
                    "Unable to return $dataType due to the following exception:"
                )
                Log.e("FLUTTER_HEALTH::ERROR", Log.getStackTraceString(e))
                result.success(null)
            }
        }
    }

    private fun convertRecordStage(
        stage: SleepSessionRecord.Stage,
        dataType: String,
        metadata: Metadata
    ): List<Map<String, Any>> {
        var sourceName = metadata.dataOrigin
            .packageName
        return listOf(
            mapOf<String, Any>(
                "uuid" to metadata.id,
                "stage" to stage.stage,
                "value" to
                        ChronoUnit.MINUTES.between(
                            stage.startTime,
                            stage.endTime
                        ),
                "date_from" to stage.startTime.toEpochMilli(),
                "date_to" to stage.endTime.toEpochMilli(),
                "source_id" to "",
                "source_name" to sourceName,
            ),
        )
    }

    private fun getAggregateData(call: MethodCall, result: Result) {
        result.success(null)
    }

    // TODO: Find alternative to SOURCE_ID or make it nullable?
    private fun convertRecord(record: Any, dataType: String): List<Map<String, Any?>> {
        val metadata = (record as Record).metadata
        when (record) {

            is StepsRecord ->
                return listOf(
                    mapOf<String, Any>(
                        "uuid" to
                                metadata.id,
                        "value" to record.count,
                        "date_from" to
                                record.startTime
                                    .toEpochMilli(),
                        "date_to" to
                                record.endTime
                                    .toEpochMilli(),
                        "source_id" to "",
                        "source_name" to
                                metadata.dataOrigin
                                    .packageName,
                        "recording_method" to
                                metadata.recordingMethod
                    ),
                )


            else ->
                throw IllegalArgumentException(
                    "Health data type not supported"
                ) // TODO: Exception or error?
        }
    }

    // TODO rewrite sleep to fit new update better --> compare with Apple and see if we should
    // not adopt a single type with attached stages approach
    private fun writeData(call: MethodCall, result: Result) {
        result.success(false)
    }

    /** Save a Workout session with options for distance and calories expended */
    private fun writeWorkoutData(call: MethodCall, result: Result) {
        result.success(false)
    }

    /** Save a Blood Pressure measurement with systolic and diastolic values */
    private fun writeBloodPressure(call: MethodCall, result: Result) {
        result.success(false)
    }

    /** Delete records of the given type in the time range */
    private fun deleteData(call: MethodCall, result: Result) {
        result.success(false)
    }

    private val mapToType =
        hashMapOf(
            STEPS to StepsRecord::class,
            )


}
