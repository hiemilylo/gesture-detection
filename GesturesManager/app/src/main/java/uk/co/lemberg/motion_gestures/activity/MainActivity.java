package uk.co.lemberg.motion_gestures.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.codekidlabs.storagechooser.StorageChooser;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.github.mikephil.charting.utils.ColorTemplate;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import uk.co.lemberg.motion_gestures.R;
import uk.co.lemberg.motion_gestures.adapter.ColorsAdapter;
import uk.co.lemberg.motion_gestures.dialogs.DialogResultListener;
import uk.co.lemberg.motion_gestures.dialogs.PromptDialog;
import uk.co.lemberg.motion_gestures.settings.AppSettings;
import uk.co.lemberg.motion_gestures.utils.Label;
import uk.co.lemberg.motion_gestures.utils.TimestampAxisFormatter;
import uk.co.lemberg.motion_gestures.utils.Utils;

import static android.content.Context.SENSOR_SERVICE;

public class MainActivity extends AppCompatActivity implements DialogResultListener {

	private static final String TAG = MainActivity.class.getSimpleName();
	private static final String SHOW_FILE_NAME_DLG_TAG = "file_name_dialog";
	private static final int SHOW_FILE_NAME_DLG_REQ_CODE = 1000;
	private static final int WRITE_PERMISSIONS_REQ_CODE = 1001;

	private static final int GESTURE_DURATION_MS = 2000000; // 2.56 sec
	private static final int GESTURE_SAMPLES = 200;

	private AppSettings settings;

	private Spinner spinLabels;
	private LineChart chart;
	private ToggleButton toggleRec;
	private TextView txtStats;

	private SensorManager sensorManager;
	private Sensor l_accelerometer, accelerometer, magnetic, gyroscope, rotation_vector, gravity;

	private boolean recStarted = false;
	private long firstTimestamp = -1;
	private float lastTimestamp = -1;
	private Map<Integer, Double> currData = new HashMap<>();
	private int selectedEntryIndex = -1;

	private long fileNameTimestamp = -1;
	private float[] rot = new float[9];
	private float[] vals = new float[3];
	private float[] accl = null;
	private float[] mag = null;

	// Change me to toggle which data is shown on graph
	private int chosenSensor = Sensor.TYPE_GRAVITY;
	private float graphMax = 10f;


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		settings = AppSettings.getAppSettings(this);
		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		l_accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
		accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		magnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
		rotation_vector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
		gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
		initViews();
		fillStatus();
	}

	@Override
	protected void onResume() {
		super.onResume();
		checkPermissions();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem menuSave = menu.findItem(R.id.action_save);
		MenuItem menuSaveAs = menu.findItem(R.id.action_save_as);

		boolean isSampleSelected = isSampleSelected();
		menuSave.setEnabled(isSampleSelected);
		menuSave.getIcon().setAlpha(isSampleSelected ? 255 : 70);

		boolean isDataAvailable = isDataAvailable();
		menuSaveAs.setEnabled(isDataAvailable);
		menuSaveAs.getIcon().setAlpha(isDataAvailable ? 255 : 70);

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.action_working_dir:
				showWorkingDirDlg();
				return true;
			case R.id.action_load:
				showLoadDialog();
				return true;
			case R.id.action_save:
				saveSelectionDataToast(Utils.generateFileName(getCurrentLabel(), System.currentTimeMillis()));
				moveSelectionToNext();
				return true;
			case R.id.action_save_as:
				showSaveDirDlgIfNeeded();
				return true;
			case R.id.action_test:
				launchTestActivity();
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	// region permissions stuff
	@Override
	public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
		switch (requestCode) {
			case WRITE_PERMISSIONS_REQ_CODE: {
				// If request is cancelled, the result arrays are empty.
				if ((grantResults.length > 0) && (grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
					// permission was granted
				} else {
					// permission denied
					showToast(getString(R.string.no_permissions));
					finish();
				}
			}
		}
	}

	private boolean checkPermissions() {
	    Log.d("info", "checking permissions");
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_PERMISSIONS_REQ_CODE);
			Log.d("info", "requesting");
			return false;
		}
		return true;
	}
	// endregion

	@Override
	public void onDialogResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case SHOW_FILE_NAME_DLG_REQ_CODE:
				if (resultCode == Activity.RESULT_OK) {
					String fileName = data.getStringExtra(PromptDialog.VALUE);
					saveAllDataToast(fileName);
				}
				break;
		}
	}

	private void setLineData(LineData lineData) {
		chart.setData(lineData);
	}

	private LineData getLineData() {
		return chart.getLineData();
	}

	private void initViews() {
		spinLabels = findViewById(R.id.spinLabels);
		chart = findViewById(R.id.chart);
		toggleRec = findViewById(R.id.toggleRec);
		txtStats = findViewById(R.id.txtStats);

		toggleRec.setOnClickListener(clickListener);

		spinLabels.setAdapter(new ColorsAdapter(this, Arrays.asList(Label.values())));

		//chart.setLogEnabled(true);
		chart.setTouchEnabled(true);
		chart.setOnChartValueSelectedListener(chartValueSelectedListener);
		chart.setData(new LineData());
		getLineData().setValueTextColor(Color.WHITE);

		chart.getDescription().setEnabled(false);
		chart.getLegend().setEnabled(true);
		chart.getLegend().setTextColor(Color.WHITE);

		XAxis xAxis = chart.getXAxis();
		xAxis.setTextColor(Color.WHITE);
		xAxis.setDrawGridLines(true);
		xAxis.setAvoidFirstLastClipping(true);
		xAxis.setEnabled(true);

		xAxis.setValueFormatter(new TimestampAxisFormatter());

		YAxis leftAxis = chart.getAxisLeft();
		leftAxis.setEnabled(false);

		YAxis rightAxis = chart.getAxisRight();
		rightAxis.setTextColor(Color.WHITE);
		rightAxis.setAxisMaximum(graphMax);
		rightAxis.setAxisMinimum(-graphMax);
		rightAxis.setDrawGridLines(true);

	}

	private final View.OnClickListener clickListener = new View.OnClickListener() {
		@Override
		public void onClick(View v) {
			switch (v.getId()) {
				case R.id.toggleRec:
					if (recStarted) stopRecInt();
					else startRec();
					break;
			}
		}
	};

	private void startRec() {
		if (startRecInt()) {
			getLineData().clearValues();
			for (int i = 0; i < LINE_DESCRIPTIONS.length; i++) {
                ILineDataSet set = createLineDataSet(LINE_DESCRIPTIONS[i], LINE_COLORS[i]);
                getLineData().addDataSet(set);
            }
		}
		else {
			Toast.makeText(this, R.string.sensor_failed, Toast.LENGTH_SHORT).show();
			toggleRec.setChecked(false);
		}
	}

	private boolean startRecInt() {
		if (!recStarted) {
			firstTimestamp = -1;
			fileNameTimestamp = System.currentTimeMillis();
			chart.highlightValue(null, true);
            sensorManager.registerListener(sensorEventListener, gyroscope, GESTURE_DURATION_MS / GESTURE_SAMPLES);
            sensorManager.registerListener(sensorEventListener, accelerometer, GESTURE_DURATION_MS / GESTURE_SAMPLES);
			sensorManager.registerListener(sensorEventListener, l_accelerometer, GESTURE_DURATION_MS / GESTURE_SAMPLES);
			sensorManager.registerListener(sensorEventListener, magnetic, GESTURE_DURATION_MS / GESTURE_SAMPLES);
			sensorManager.registerListener(sensorEventListener, gravity, GESTURE_DURATION_MS / GESTURE_SAMPLES);
			recStarted = sensorManager.registerListener(sensorEventListener, rotation_vector, GESTURE_DURATION_MS / GESTURE_SAMPLES);
			// recStarted = sensorManager.registerListener(sensorEventListener, accelerometer, GESTURE_DURATION_MS / GESTURE_SAMPLES);
		}
		return recStarted;
	}

	private void stopRecInt() {
		if (recStarted) {
			sensorManager.unregisterListener(sensorEventListener);
			recStarted = false;
		}
	}

	private final OnChartValueSelectedListener chartValueSelectedListener = new OnChartValueSelectedListener() {
		@Override
		public void onValueSelected(Entry e, Highlight h) {
			ILineDataSet set = getLineData().getDataSetByIndex(h.getDataSetIndex());
			selectedEntryIndex = set.getEntryIndex(e);
			supportInvalidateOptionsMenu();
			fillStatus();

			// highlight ending line
			Entry endEntry = getSelectionEndEntry();
			if (endEntry != null) {
				Highlight endHightlight = new Highlight(endEntry.getX(), endEntry.getY(), h.getDataSetIndex());
				chart.highlightValues(new Highlight[]{h, endHightlight});
			}
		}

		@Override
		public void onNothingSelected() {
			selectedEntryIndex = -1;
			supportInvalidateOptionsMenu();
			fillStatus();
		}
	};

	/**
	 *
	 * @return null if not exist
	 */
	private Entry getSelectionEndEntry() {
		int index = selectedEntryIndex + GESTURE_SAMPLES;
		if (index >= chart.getLineData().getDataSetByIndex(0).getEntryCount())
			return null;

		return chart.getLineData().getDataSetByIndex(0).getEntryForIndex(index);
	}

	private void moveSelectionToNext() {
		int current = selectedEntryIndex != -1 ? selectedEntryIndex : 0;
		current += GESTURE_SAMPLES;

		ILineDataSet dataSet = getLineData().getDataSetByIndex(0);
		while (current < dataSet.getEntryCount()) {
			Entry e = dataSet.getEntryForIndex(current);
			if (Math.abs(e.getY()) > 3) break;
			current++;
		}

		if (current == dataSet.getEntryCount())
			current = -1;
		else
		{
			current -= 20;
			if (current < -1) current = -1;
		}

		Entry e = current != -1 ? dataSet.getEntryForIndex(current) : null;
		if (e != null) {
			Highlight h = new Highlight(e.getX(), e.getY(), 0);
			chart.highlightValue(h, true);
		}
		else {
			chart.highlightValue(null, true);
		}

		supportInvalidateOptionsMenu();
		fillStatus();
	}

	private void addDataFromMap() {
        addPoint(getLineData(), X_INDEX, lastTimestamp, currData.get(0).floatValue());
        addPoint(getLineData(), Y_INDEX, lastTimestamp, currData.get(1).floatValue());
        addPoint(getLineData(), Z_INDEX, lastTimestamp, currData.get(2).floatValue());
        addPoint(getLineData(), G_INDEX, lastTimestamp, currData.get(3).floatValue());
        lastTimestamp = -1;
        currData = new HashMap<>();

        chart.notifyDataSetChanged();
        chart.invalidate();
        supportInvalidateOptionsMenu();
        fillStatus();
    }

	private final SensorEventListener sensorEventListener = new SensorEventListener() {
		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {}

		@Override
		public void onSensorChanged(SensorEvent event) {
			if (firstTimestamp == -1) firstTimestamp = event.timestamp;
			long entryTimestampFixed = event.timestamp - firstTimestamp;
            final float floatTimestampMicros = entryTimestampFixed / 1000000f;
            if (lastTimestamp == -1) lastTimestamp = floatTimestampMicros;

            if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR && chosenSensor == Sensor.TYPE_ROTATION_VECTOR) {
                sensorManager.getRotationMatrixFromVector(rot, event.values);
                vals = sensorManager.getOrientation(rot, vals);
                final float azimuth = event.values[0];
                final float pitch = event.values[1];
                final float roll = event.values[2];
                currData.put(0, (double) azimuth);
                currData.put(1, (double) pitch);
                currData.put(2, (double) roll);
                currData.put(3, (double) 0.0);
                accl = null;
                mag = null;
                addDataFromMap();
            } else if (event.sensor.getType() == chosenSensor) {
				final float x = event.values[0];
				final float y = event.values[1];
				final float z = event.values[2];
				currData.put(0, (double) x);
				currData.put(1, (double) y);
				currData.put(2, (double) z);
				currData.put(3, (double) 0.0);
				addDataFromMap();
			}
//            if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD && mag == null) {
//            	mag = new float[3];
//            	mag[0] = event.values[0];
//            	mag[1] = event.values[1];
//            	mag[2] = event.values[2];
//            	//Log.d("info", "found mag");
//			} else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER && accl == null) {
//                accl = new float[3];
//                accl[0] = event.values[0];
//                accl[1] = event.values[1];
//                accl[2] = event.values[2];
//                //Log.d("info", "found accl");
//			}
//			if (accl != null && mag != null) {
//				// Can calculate rotation vector
//				sensorManager.getRotationMatrix(rot, null, accl, mag);
//				vals = sensorManager.getOrientation(rot, vals);
//				final float azimuth = vals[0];
//				final float pitch = vals[1];
//				final float roll = vals[2];
//				currData.put(0, (double)azimuth);
//				currData.put(1, (double)pitch);
//				currData.put(2, (double)roll);
//				currData.put(3, (double)0.0);
//                accl = null;
//				mag = null;
//				addDataFromMap();
//			}
//            if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
//                if (!currData.containsKey(0)) {
//                    final float x = event.values[0];
//                    final float y = event.values[1];
//                    final float z = event.values[2];
//                    currData.put(0, (double)x);
//                    currData.put(1, (double)y);
//                    currData.put(2, (double)z);
//                    currData.put(3, (double)0.0);
//                    addDataFromMap();
//                }
//            }
//            if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) {
//                if (!currData.containsKey(3)) {
//                    final float g = event.values[1];
//                    currData.put(3, (double)g);
//                }
//            } else {
//                if (!currData.containsKey(0)) {
//                    final float x = event.values[0];
//                    final float y = event.values[1];
//                    final float z = event.values[2];
//                    currData.put(0, (double)x);
//                    currData.put(1, (double)y);
//                    currData.put(2, (double)z);
//                    currData.put(3, (double)0.0);
//                    addDataFromMap();
//                }
//            }
//            if (currData.size() == LINE_DESCRIPTIONS.length) {
//                addDataFromMap();
//            }
		}
	};

	private boolean isDataAvailable() {
		if (getLineData().getDataSetCount() == 0) return false;
		return getLineData().getDataSetByIndex(0).getEntryCount() != 0;
	}

	private boolean isSampleSelected() {
		if (getLineData().getDataSetCount() == 0) return false;
		if (selectedEntryIndex == -1) return false;
		if (getLineData().getDataSetByIndex(0).getEntryCount() - selectedEntryIndex < GESTURE_SAMPLES) return false;
		return true;
	}

	private void fillStatus() {
		txtStats.setText(formatStatsText());
	}

	private String formatStatsText() {
		return String.format("Pos: %s/%s s\nSamples: %d", getXLabelAtHighlight(), getXLabelAtEnd(), getSamplesCount());
	}

	private String getXLabelAtHighlight() {
		if ((selectedEntryIndex == -1) || (getLineData().getDataSetCount() == 0)) return "-";
		return String.format("%.03f", getLineData().getDataSetByIndex(0).getEntryForIndex(selectedEntryIndex).getX() / 1000f);
	}

	private String getXLabelAtEnd() {
		if ((getLineData().getDataSetCount() == 0) || (getLineData().getDataSetByIndex(0).getEntryCount() == 0)) return "-";
		return String.format("%.03f", getLineData().getDataSetByIndex(0).getEntryForIndex(getLineData().getDataSetByIndex(0).getEntryCount() - 1).getX() / 1000f);
	}

	private int getSamplesCount() {
		if (getLineData().getDataSetCount() == 0) return 0;
		return getLineData().getDataSetByIndex(0).getEntryCount();
	}

	private void showLoadDialog() {
		StorageChooser chooser = new StorageChooser.Builder()
			.withActivity(MainActivity.this)
			.withFragmentManager(getFragmentManager())
			.allowCustomPath(true)
			.shouldResumeSession(true)
			.setType(StorageChooser.FILE_PICKER)
			.build();

		chooser.show();

		chooser.setOnSelectListener(new StorageChooser.OnSelectListener() {
			@Override
			public void onSelect(String path) {
				loadDataToast(path);
			}
		});
	}

	private void showWorkingDirDlg() {
		StorageChooser chooser = new StorageChooser.Builder()
			.withActivity(MainActivity.this)
			.withFragmentManager(getFragmentManager())
			.allowCustomPath(true)
			.allowAddFolder(true)
			.shouldResumeSession(true)
			.setType(StorageChooser.DIRECTORY_CHOOSER)
			.build();

		chooser.show();

		chooser.setOnSelectListener(new StorageChooser.OnSelectListener() {
			@Override
			public void onSelect(String path) {
				settings.setWorkingDir(path);
				settings.saveDeferred();
			}
		});
	}

	private void showSaveDirDlgIfNeeded() {
		if (Utils.isWorkingDirDefault(settings)) {
			StorageChooser chooser = new StorageChooser.Builder()
				.withActivity(MainActivity.this)
				.withFragmentManager(getFragmentManager())
				.allowCustomPath(true)
				.setType(StorageChooser.DIRECTORY_CHOOSER)
				.build();

			chooser.show();

			chooser.setOnSelectListener(new StorageChooser.OnSelectListener() {
				@Override
				public void onSelect(String path) {
					settings.setWorkingDir(path);
					settings.saveDeferred();

					showFileNameDlg();
				}
			});

			return;
		}

		showFileNameDlg();
	}

	private void showFileNameDlg() {
		PromptDialog dlg = PromptDialog.newInstance(SHOW_FILE_NAME_DLG_REQ_CODE, getString(R.string.enter_file_name),
			Utils.generateFileName(getCurrentLabel(), fileNameTimestamp), getString(R.string.file_name));
		dlg.show(getSupportFragmentManager(), SHOW_FILE_NAME_DLG_TAG);
	}

	private void showToast(String str) {
		Toast toast = Toast.makeText(this, str, Toast.LENGTH_SHORT);
		toast.show();
	}

	private String getCurrentLabel() {
		Label label = (Label) spinLabels.getSelectedItem();
		if (label == null) return "{null}";
		return label.toString();
	}

	private void saveSelectionDataToast(String fileName) {
		try {
		    Log.d("info", "saving");
			Utils.saveLineData(new File(settings.getWorkingDir(), fileName), getLineData(), selectedEntryIndex, GESTURE_SAMPLES);
			showToast(getString(R.string.data_saved));
		}
		catch (IOException e) {
			Log.e(TAG, getString(R.string.failed_to_save), e);
			showToast(getString(R.string.failed_to_save_error) + e);
		}
	}

	private void saveAllDataToast(String fileName) {
		try {
			Utils.saveLineData(new File(settings.getWorkingDir(), fileName), getLineData());
			showToast(getString(R.string.data_saved));
		}
		catch (IOException e) {
			Log.e(TAG, getString(R.string.failed_to_save), e);
			showToast(getString(R.string.failed_to_save_error) + e);
		}
	}

	private void loadDataToast(String file) {
		try {
			Pair<Utils.FileName, LineData> data = loadLineData(file);

			setLineData(data.second);
			chart.notifyDataSetChanged();
			chart.highlightValue(null, true);
			chart.invalidate();

			fileNameTimestamp = data.first.timestap;
			spinLabels.setSelection(data.first.label.ordinal());

			fillStatus();
		}
		catch (Exception e) {
			Log.e(TAG, getString(R.string.failed_to_load), e);
			showToast(getString(R.string.failed_to_load_error) + e);
		}
	}

	private void launchTestActivity() {
		startActivity(new Intent(this, TestActivity.class));
	}

	// region chart helper methods
	private static final String[] LINE_DESCRIPTIONS = {"X", "Y", "Z", "G"};
	private static final int[] LINE_COLORS = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF};

	private static final int X_INDEX = 0;
	private static final int Y_INDEX = 1;
	private static final int Z_INDEX = 2;
	private static final int G_INDEX = 3;

	private static LineDataSet createLineDataSet(String description, int color) {
		LineDataSet set = new LineDataSet(null, description);
		Log.d("info", "d: " + description + " c: " + color);
		set.setAxisDependency(YAxis.AxisDependency.RIGHT);
		set.setColor(color);
		set.setDrawCircles(false);
		set.setDrawCircleHole(false);
		set.setLineWidth(1f);
		set.setFillAlpha(65);
		set.setFillColor(ColorTemplate.getHoloBlue());
		set.setHighLightColor(Color.WHITE);
		set.setValueTextColor(Color.WHITE);
		set.setValueTextSize(9f);
		set.setDrawValues(false);
		set.setDrawHighlightIndicators(true);
		set.setDrawIcons(false);
		set.setDrawHorizontalHighlightIndicator(false);
		set.setDrawFilled(false);
		return set;
	}


	private static void addPoint(LineData data, int dataSetIndex, float x, float y) {
		ILineDataSet set = data.getDataSetByIndex(dataSetIndex);
		//Log.d("info", "data: " + dataSetIndex);

		if (set == null) {
			set = createLineDataSet(LINE_DESCRIPTIONS[dataSetIndex], LINE_COLORS[dataSetIndex]);
			data.addDataSet(set);
		}

		data.addEntry(new Entry(x, y), dataSetIndex);

		data.notifyDataChanged();
	}

	private static Pair<Utils.FileName, LineData> loadLineData(String strFile) throws Exception {
		Pair<Utils.FileName, List<Utils.FileEntry>> pair = Utils.loadData(strFile);

		LineData lineData = new LineData();
		for (int i = 0; i < LINE_DESCRIPTIONS.length; i++) {
		    Log.d("info", "i: " + i + " color: " + LINE_COLORS[i]);
			lineData.addDataSet(createLineDataSet(LINE_DESCRIPTIONS[i], LINE_COLORS[i]));
		}

		for (Utils.FileEntry entry : pair.second) {
			addPoint(lineData, X_INDEX, entry.timestamp, entry.x);
			addPoint(lineData, Y_INDEX, entry.timestamp, entry.y);
			addPoint(lineData, Z_INDEX, entry.timestamp, entry.z);
			addPoint(lineData, G_INDEX, entry.timestamp, entry.gx);
		}

		return new Pair<>(pair.first, lineData);
	}

	// endregion
}
