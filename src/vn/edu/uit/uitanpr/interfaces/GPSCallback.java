package vn.edu.oa.anpr.interfaces;

import android.location.Location;

public interface GPSCallback {
	public abstract void onGPSUpdate(Location location);
}
