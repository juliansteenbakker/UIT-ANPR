package vn.edu.uit.uitanpr;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import vn.edu.uit.uitanpr.common.PlatesListAdapter;
import vn.edu.uit.uitanpr.common.Utils;
import vn.edu.uit.uitanpr.interfaces.OnTaskCompleted;
import vn.edu.uit.uitanpr.models.BitmapWithCentroid;
import vn.edu.uit.uitanpr.views.CameraPreview;

import com.hazuu.uitanpr.neural.KohonenNetwork;
import com.hazuu.uitanpr.neural.SampleData;

public class MainActivity extends Activity implements OnTaskCompleted{
	private CameraPreview cameraPreview;
	private RelativeLayout layout;
	private PlateView plateView;
	PlatesListAdapter adapter;
	Utils utils;
	public boolean isRunningTask = false;
	public boolean isFail = false;

	public static final String PACKAGE_NAME = "vn.edu.uit.uitanpr";
	public static final String DATA_PATH = Environment
			.getExternalStorageDirectory().toString() + "/UIT-ANPR/";

	public static final String lang = "eng";

	private static final String TAG = "MainActivity.java";

	List<Point> platePointList;
	TextView resultOCR;

	KohonenNetwork net;

	static final int DOWNSAMPLE_WIDTH = 20;
	static final int DOWNSAMPLE_HEIGHT = 50;
	protected int pixelMap[];
	protected Bitmap newBitmap;
	protected double ratioX;
	protected double ratioY;
	protected int downSampleLeft;
	protected int downSampleRight;
	protected int downSampleTop;
	protected int downSampleBottom;

	private Mat mRgba;
	private Mat mGray;
	private File mCascadeFile;
	private CascadeClassifier mJavaDetector;

	MatOfRect plates;

	private float mRelativePlateSize = 0.2f;
	private int mAbsolutePlateSize = 0;

	private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
		@Override
		public void onManagerConnected(int status) {
			switch (status) {
			case LoaderCallbackInterface.SUCCESS: {
				Log.i(TAG, "OpenCV loaded successfully");

				try {
					// Load Haar training result file from application resources
					// This file from opencv_traincascade tool.
					// Load res/cascade-europe.xml file
					InputStream is = getResources().openRawResource(R.raw.europe);

					File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
					mCascadeFile = new File(cascadeDir, "europe.xml"); // Load XML file according to R.raw.cascade
					FileOutputStream os = new FileOutputStream(mCascadeFile);

					byte[] buffer = new byte[4096];
					int bytesRead;
					while ((bytesRead = is.read(buffer)) != -1) {
						os.write(buffer, 0, bytesRead);
					}
					is.close();
					os.close();

					mJavaDetector = new CascadeClassifier(
							mCascadeFile.getAbsolutePath());
					mJavaDetector.load( mCascadeFile.getAbsolutePath() );
					if (mJavaDetector.empty()) {
						Log.e(TAG, "Failed to load cascade classifier");
						mJavaDetector = null;
					} else
						Log.i(TAG, "Loaded cascade classifier from "
								+ mCascadeFile.getAbsolutePath());

					cascadeDir.delete();

				} catch (IOException e) {
					e.printStackTrace();
					Log.e(TAG, "Failed to load cascade. Exception thrown: " + e);
				}
			}
				break;
			case LoaderCallbackInterface.INIT_FAILED:
			{
				
			}
				break;
			default: {
				super.onManagerConnected(status);
			}
				break;
			}
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		super.onCreate(savedInstanceState);

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.main);
		Boolean checkOpenCV = OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_2_0, getApplicationContext(),
				mLoaderCallback);	
		if(checkOpenCV)
		{
			try {
				layout = (RelativeLayout) findViewById(R.id.mainFrame);
				plateView = new PlateView(this);
				cameraPreview = new CameraPreview(this, plateView);
				layout.addView(cameraPreview, 1);
				layout.addView(plateView, 2);

				resultOCR = new TextView(getApplicationContext());
				resultOCR.setText("Scan een kenteken");
				layout.addView(resultOCR, 3);
				RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
						resultOCR.getLayoutParams());
				lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
				lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
				lp.setMargins(0, 0, 0, 10);
				resultOCR.setTextSize(30);
				resultOCR.setBackgroundColor(Color.WHITE);
				resultOCR.setTextColor(Color.RED);
				resultOCR.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
				resultOCR.setLayoutParams(lp);
				

			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}

			utils = new Utils(getBaseContext());
			platePointList = new ArrayList<Point>();

			if (this.net == null) {
				try {

					AssetManager assetManager = getAssets();
					InputStream in = assetManager.open("neural_net.ser");

					// START IMPORT TRAINED DATA TO NETWORK
					try {
						// use buffering
						InputStream buffer = new BufferedInputStream(in);
						ObjectInput input = new ObjectInputStream(buffer);
						try {
							// deserialize the List
							this.net = (KohonenNetwork) input.readObject();
							Log.i(TAG, String.valueOf(this.net.getMap()));
						} finally {
							input.close();
						}
					} catch (ClassNotFoundException ex) {
						Log.e(TAG, "Cannot perform input. Class not found.");
					} catch (IOException ex) {
						Log.e(TAG, "Cannot perform input." + ex.getMessage());
					}

					in.close();
					// gin.close();
					// out.close();
					Log.v(TAG, "Imported trained data");
				} catch (IOException e) {
					Log.e(TAG, e.getMessage());
				}
			}
		}


	}

	@Override
	public void onPause() {
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();
		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_2_0, this,
				mLoaderCallback);
	}

	public void onDestroy() {
		super.onDestroy();
		super.onDestroy();
	}

	// This class is for Viewing a detected plate
	public class PlateView extends View implements Camera.PreviewCallback,
			OnTaskCompleted {
		public static final int SUBSAMPLING_FACTOR = 4;
		Rect[] platesArray;
		Bitmap og;
		List<Point> currentPlatePointList = new ArrayList<Point>();
		List<Rect> currentPlates = new ArrayList<Rect>();

		public PlateView(MainActivity context) throws IOException {
			super(context);
		}

		public void onPreviewFrame(final byte[] data, final Camera camera) {

			try {
				Camera.Size size = camera.getParameters().getPreviewSize();

				// Get image from camera and process it to see
				// are there any plate on it ?
				processImage(data, size.width, size.height);
				camera.addCallbackBuffer(data);
			} catch (RuntimeException e) {
				// The camera has probably just been released, ignore.
				Log.e(TAG,e.toString());
			}
		}

		protected void processImage(byte[] data, int width, int height) {

			// First, downsample our image and convert it into a grayscale
			int f = SUBSAMPLING_FACTOR;
			mRgba = new Mat(height, width, CvType.CV_8UC4);
			mGray = new Mat(height, width, CvType.CV_8UC1);

			Mat mYuv = new Mat(height + height / 2, width, CvType.CV_8UC1);
			mYuv.put(0, 0, data);

			Imgproc.cvtColor(mYuv, mGray, Imgproc.COLOR_YUV420sp2GRAY);
			Imgproc.cvtColor(mYuv, mRgba, Imgproc.COLOR_YUV2RGB_NV21, 3);

			if (mAbsolutePlateSize == 0) {
				int heightGray = mGray.rows();
				if (Math.round(heightGray * mRelativePlateSize) > 0) {
					mAbsolutePlateSize = Math.round(heightGray
							* mRelativePlateSize);
				}
				Log.d("Plate size", String.valueOf(mAbsolutePlateSize));
			}

			// This variable is used to to store the detected plates in the result
			plates = new MatOfRect();

			if (mJavaDetector != null)
				mJavaDetector.detectMultiScale(
						mGray,
						plates,
						1.1,
						2,
						2,
						new Size(mAbsolutePlateSize, mAbsolutePlateSize),
						new Size()
				);

			postInvalidate();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			Paint paint = new Paint();
			paint.setColor(Color.GREEN);

			paint.setTextSize(10);
			if (plates != null) {
				paint.setStrokeWidth(3);
				paint.setStyle(Paint.Style.STROKE);

				platesArray = plates.toArray();
				boolean isHasNewPlate = false;
				currentPlates.clear();

				for (int i = 0; i < platesArray.length; i++) {
					int x = platesArray[i].x;
					int y = platesArray[i].y;
					int w = platesArray[i].width;
					int h = platesArray[i].height;

					// Draw a Green Rectangle surrounding the Number Plate !
					// Congratulations ! You found the plate area :-)
					canvas.drawRect(x, y, (x + w), (y + h), paint);

					Log.i("Plate found"," Found a plate !!!");

					// isNewPlate?
					Point platePoint = new Point(platesArray[i].x,
							platesArray[i].y);
					currentPlatePointList.add(platePoint);
					currentPlates.add(platesArray[i]);
					if (utils.isNewPlate(platePointList, platePoint)) {
						isHasNewPlate = true;
					}
				}

				if (platesArray.length > 0) {
					platePointList.clear();
					platePointList.addAll(currentPlatePointList);
				} else {
					platePointList.clear();
				}

				// If isHasNewPlate --> get sub images (ROI) --> Add to Adapter
				// (from currentPlates)
				if ((isHasNewPlate) && !isRunningTask) {
					Log.e(TAG, "START DoOCR task!!!!");
					new DoOCR(currentPlates, mRgba, this).execute();
				}
			}
		}

		public void updateResult(String result) {
			// TODO Auto-generated method stub
			resultOCR.setText(result);
			
		}

		public class DoOCR extends AsyncTask<Void, Bitmap, String> {

			private List<Rect> currentPlatesOnAsy;
			private Mat originImageOnAsy;
			private OnTaskCompleted listener;

			public DoOCR(List<Rect> currentPlates, Mat originImage,
					OnTaskCompleted listener) {
				this.currentPlatesOnAsy = new ArrayList<Rect>(currentPlates);
				this.originImageOnAsy = originImage;
				this.listener = listener;
			}

			@Override
			protected void onPreExecute() {
				isRunningTask = true;
			}

			@Override
			protected String doInBackground(Void... params) {
				Iterator<Rect> iterator = currentPlatesOnAsy.iterator();
				BitmapWithCentroid tempBitmap;
				long start, timeRequired;
				String result = "";

				while (iterator.hasNext()) {
					start = System.currentTimeMillis();
					Rect plateRect = iterator.next();
					Mat plateImage;
					List<BitmapWithCentroid> charList = new ArrayList<BitmapWithCentroid>();

					int x = plateRect.x, y = plateRect.y, w = plateRect.width, h = plateRect.height;

					Rect roi = new Rect((int) (x), (int) (y), (int) (w),
							(int) (h));

					plateImage = new Mat(roi.size(), originImageOnAsy.type());

					plateImage = originImageOnAsy.submat(roi);

					Mat plateImageResized = new Mat();

					Imgproc.resize(plateImage, plateImageResized, new Size(680,
							492));

					Mat plateImageGrey = new Mat();

					Imgproc.cvtColor(plateImageResized, plateImageGrey,
							Imgproc.COLOR_BGR2GRAY, 1);
					Imgproc.medianBlur(plateImageGrey, plateImageGrey, 1);
					Imgproc.adaptiveThreshold(plateImageGrey, plateImageGrey,
							255, Imgproc.ADAPTIVE_THRESH_MEAN_C,
							Imgproc.THRESH_BINARY, 85, 5);

					List<MatOfPoint> contours = new ArrayList<MatOfPoint>();

					Mat hierarchy = new Mat(plateImageGrey.rows(),
							plateImageGrey.cols(), CvType.CV_8UC1,
							new Scalar(0));

					Imgproc.findContours(plateImageGrey, contours, hierarchy,
							Imgproc.CHAIN_APPROX_SIMPLE, Imgproc.RETR_LIST);

					String recognizedText = "";
					timeRequired = System.currentTimeMillis() - start;
					Log.e(TAG, "Time for find countour: " + timeRequired);
					Log.e(TAG, "Start loop!!!" + contours.size());
					start = System.currentTimeMillis();

					for (int i = 0; i < contours.size(); i++) {
						List<Point> goodpoints = new ArrayList<Point>();
						Mat contour = contours.get(i);
						int num = (int) contour.total();
						int buff[] = new int[num * 2]; // [x1, y1, x2, y2, ...]
						contour.get(0, 0, buff);
						for (int q = 0; q < num * 2; q = q + 2) {
							goodpoints.add(new Point(buff[q], buff[q + 1]));
						}

						MatOfPoint points = new MatOfPoint();
						points.fromList(goodpoints);
						Rect boundingRect = Imgproc.boundingRect(points);

						if (((boundingRect.height / boundingRect.width) >= 1.5)
								&& ((boundingRect.height / boundingRect.width) <= 3.0)
								&& ((boundingRect.height * boundingRect.width) >= 5000)) {

							int cx = boundingRect.x + (boundingRect.width / 2);
							int cy = boundingRect.y + (boundingRect.height / 2);

							Point centroid = new Point(cx, cy);

							if (centroid.y >= 120 && centroid.y <= 400
									&& centroid.x >= 100 && centroid.x <= 590) {

								int calWidth = (boundingRect.width + 5)
										- (boundingRect.width + 5) % 4;

								Rect cr = new Rect(boundingRect.x,
										boundingRect.y, calWidth,
										boundingRect.height);

								Mat charImage = new Mat(
										cr.size(),
										plateImageResized.type());

								charImage = plateImageResized.submat(cr);

								Mat charImageGrey = new Mat(charImage.size(),
										charImage.type());
								Imgproc.cvtColor(charImage, charImageGrey,
										Imgproc.COLOR_BGR2GRAY, 1);

								Imgproc.adaptiveThreshold(charImageGrey,
										charImageGrey, 255,
										Imgproc.ADAPTIVE_THRESH_MEAN_C,
										Imgproc.THRESH_BINARY, 85, 5);

								Bitmap charImageBitmap = Bitmap.createBitmap(
										charImageGrey.width(),
										charImageGrey.height(),
										Bitmap.Config.ARGB_8888);

								org.opencv.android.Utils.matToBitmap(
										charImageGrey, charImageBitmap);

								tempBitmap = new BitmapWithCentroid(
										charImageBitmap, centroid);
								charList.add(tempBitmap);
							}
						}
						// }
					}

					timeRequired = System.currentTimeMillis() - start;
					Log.e(TAG, "Passed the loop");
					Log.e(TAG, "Time for OCR: " + timeRequired);

					start = System.currentTimeMillis();
					Collections.sort(charList);

					SampleData data = new SampleData('?', DOWNSAMPLE_WIDTH,
							DOWNSAMPLE_HEIGHT);
					Log.d("charlist ", charList.toString());

					for (int index = 0; index < charList.size(); index++) {
						newBitmap = charList.get(index).getBitmap();

						final int wi = newBitmap.getWidth();
						final int he = newBitmap.getHeight();

						pixelMap = new int[newBitmap.getHeight()
								* newBitmap.getWidth()];
						newBitmap.getPixels(pixelMap, 0, newBitmap.getWidth(),
								0, 0, newBitmap.getWidth(),
								newBitmap.getHeight());

						findBounds(wi, he);

						ratioX = (double) (downSampleRight - downSampleLeft)
								/ (double) data.getWidth();
						ratioY = (double) (downSampleBottom - downSampleTop)
								/ (double) data.getHeight();

						for (int yy = 0; yy < data.getHeight(); yy++) {
							for (int xx = 0; xx < data.getWidth(); xx++) {
								if (downSampleRegion(xx, yy)) {
									data.setData(xx, yy, true);
								} else {
									data.setData(xx, yy, false);
								}
							}
						}

						final double input[] = new double[20 * 50];
						int idx = 0;
						for (int yy = 0; yy < data.getHeight(); yy++) {
							for (int xx = 0; xx < data.getWidth(); xx++) {
								input[idx++] = data.getData(xx, yy) ? 0.5
										: -0.5;
							}
						}

						double normfac[] = new double[1];
						double synth[] = new double[1];

						int best = net.winner(input, normfac, synth);

						recognizedText += net.getMap()[best];
						Log.e(TAG, "Plate number:" + recognizedText);
					}

					recognizedText = utils.formatPlateNumber(recognizedText);
					Log.e(TAG, "Plate number new format:" + recognizedText);
					if (TextUtils.isEmpty(result))
						result = recognizedText;
					else
						result += "\n" + recognizedText;

					timeRequired = System.currentTimeMillis() - start;
					Log.e(TAG, "Time: " + timeRequired);
				}
				return result;
			}

			@Override
			protected void onProgressUpdate(Bitmap... values) {
				super.onProgressUpdate(values);
			}

			@Override
			protected void onPostExecute(String aResult) {
				isRunningTask = false;
				if (!TextUtils.isEmpty(aResult)) {
					isFail = false;
					Log.i("Kenteken", "wel kunnen lezen");
					listener.updateResult(aResult);
				} else {
					isFail = true;
					Log.i("Kenteken", "niet kunnen lezen");
			}

			}

		}

	}

	protected boolean downSampleRegion(final int x, final int y) {
		final int w = this.newBitmap.getWidth();
		final int startX = (int) (this.downSampleLeft + (x * this.ratioX));
		final int startY = (int) (this.downSampleTop + (y * this.ratioY));
		final int endX = (int) (startX + this.ratioX);
		final int endY = (int) (startY + this.ratioY);

		for (int yy = startY; yy <= endY; yy++) {
			for (int xx = startX; xx <= endX; xx++) {
				final int loc = xx + (yy * w);

				if (this.pixelMap[loc] != -1) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * This method is called to automatically crop the image so that whitespace
	 * is removed.
	 * 
	 * @param w
	 *            The width of the image.
	 * @param h
	 *            The height of the image
	 */
	protected void findBounds(final int w, final int h) {
		// top line
		for (int y = 0; y < h; y++) {
			if (!hLineClear(y)) {
				this.downSampleTop = y;
				break;
			}

		}
		// bottom line
		for (int y = h - 1; y >= 0; y--) {
			if (!hLineClear(y)) {
				this.downSampleBottom = y;
				break;
			}
		}
		// left line
		for (int x = 0; x < w; x++) {
			if (!vLineClear(x)) {
				this.downSampleLeft = x;
				break;
			}
		}

		// right line
		for (int x = w - 1; x >= 0; x--) {
			if (!vLineClear(x)) {
				this.downSampleRight = x;
				break;
			}
		}
	}

	/**
	 * This method is called internally to see if there are any pixels in the
	 * given scan line. This method is used to perform autocropping.
	 * 
	 * @param y
	 *            The horizontal line to scan.
	 * @return True if there were any pixels in this horizontal line.
	 */
	protected boolean hLineClear(final int y) {
		final int w = this.newBitmap.getWidth();
		for (int i = 0; i < w; i++) {
			if (this.pixelMap[(y * w) + i] != -1) {
				return false;
			}
		}
		return true;
	}

	/**
	 * This method is called to determine ....
	 * 
	 * @param x
	 *            The vertical line to scan.
	 * @return True if there are any pixels in the specified vertical line.
	 */
	protected boolean vLineClear(final int x) {
		final int w = this.newBitmap.getWidth();
		final int h = this.newBitmap.getHeight();
		for (int i = 0; i < h; i++) {
			if (this.pixelMap[(i * w) + x] != -1) {
				return false;
			}
		}
		return true;
	}

	public void updateResult(String result) {
		// TODO Auto-generated method stub

		resultOCR.setText(result);
	}


}
