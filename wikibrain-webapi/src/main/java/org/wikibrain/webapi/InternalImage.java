package org.wikibrain.webapi;

import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.RawImage;

/**
 * Created by Josh on 4/6/16.
 */
public class InternalImage extends RawImage implements Comparable<InternalImage> {
    final private String method;
    final private double score;
    final private String title;
    String debugString = "";

    InternalImage(Language language, int sourceId, String name, String pageLocation, String imageLocation,
                  String caption, String method, double score, String title) {
        super(language, sourceId, name, pageLocation, imageLocation, caption);
        this.method = method;
        this.score = score;
        this.title = title;
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

    String generateDebugString() {
        return method + " (" + score + ") : " + debugString;
    }

    @Override
    public int compareTo(InternalImage o) {
        return ((Double)score).compareTo(o.getScore());
    }
}
