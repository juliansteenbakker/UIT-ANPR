package vn.edu.oa.anpr.interfaces;


public interface CameraSupport {
    CameraSupport open(int cameraId);
    int getOrientation(int cameraId);
}
