package org.wikibrain.webapi;

import org.json.JSONObject;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.RawImage;

/**
 * Created by Josh on 4/6/16.
 */
public class InternalImage extends RawImage implements Comparable<InternalImage> {
    final private String method;
    final private double score;
    final private String title;
    private JSONObject json;

    InternalImage(String name, String imageLocation, String caption, boolean isPhotograph, int width, int height,
                  String method, double score, String title) {
        super(name, imageLocation, caption, isPhotograph, width, height);
        this.method = method;
        this.score = score;
        this.title = title;
        json = new JSONObject();

        addDebugData("method", method);
        addDebugData("score", Double.toString(score));
        addDebugData("title", title);
        addDebugData("caption", caption);
    }

    String getMethod() {
        return method;
    }

    double getScore() {
        return score;
    }

    String getTitle() {
        return title;
    }

    void addDebugData(String key, String value) {
        json.put(key, value);
    }

    String generateDebugString() {
        return json.toString();
    }

    @Override
    public int compareTo(InternalImage o) {
        return ((Double)score).compareTo(o.getScore());
    }
}
