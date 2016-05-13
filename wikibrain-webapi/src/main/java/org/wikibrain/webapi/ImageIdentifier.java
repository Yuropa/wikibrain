package org.wikibrain.webapi;

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;

/**
 * Created by Josh on 5/10/16.
 */
public class ImageIdentifier {
    static int[][] histogramFromImage(BufferedImage image) {
        final int channels = 3;
        final int colorsBins = 256;

        int[][] histogram = new int[channels][colorsBins];

        for (int i = 0; i < channels; i++) {
            for (int j = 0; j < colorsBins; j++) {
                histogram[i][j] = 0;
            }
        }

        for (int i = 0; i < image.getWidth(); i++) {
            for (int j = 0; j < image.getHeight(); j++) {
                Color pixel = new Color(image.getRGB(i, j));
                ColorSpace colorSpace = pixel.getColorSpace();

                histogram[0][pixel.getRed()]++;
                histogram[1][pixel.getGreen()]++;
                histogram[2][pixel.getBlue()]++;
            }
        }

        return histogram;
    }

    static boolean isImagePhotograph(BufferedImage image) {
        int[][] histogram = histogramFromImage(image);

        int threshold = 120; // 40 * 3

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 255; j++) {
                if (histogram[i][j] > 1) {
                    threshold--;
                }

                if (threshold <= 0) {
                    return true;
                }
            }
        }

        return false;
    }
}
