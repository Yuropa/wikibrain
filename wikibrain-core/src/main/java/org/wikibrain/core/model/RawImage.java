package org.wikibrain.core.model;

import org.apache.batik.dom.svg.SVGDOMImplementation;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.apache.batik.util.SVGConstants;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.core.lang.Language;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.net.URLEncoder;

import org.apache.batik.transcoder.*;

import org.apache.commons.codec.binary.Base64;

/**
 * Created by Josh on 3/30/16.
 */
public class RawImage {
    private static final Logger LOG = LoggerFactory.getLogger(RawImage.class);
    private final Language language;
    private final int sourceId;
    private final String name;
    private final String pageLocation;
    private final String imageLocation;
    private final String caption;
    private final boolean isPhotograph;
    private final int width;
    private final int height;
    private static int DEFAULT_IMAGE_WIDTH = -1;

    public RawImage(Language language, int sourceId, String name, String pageLocation, String imageLocation, String caption,
                    boolean isPhotograph, int width, int height) {
        this.language = language;
        this.sourceId = sourceId;
        this.name = name;
        this.pageLocation = pageLocation;
        this.imageLocation = imageLocation;
        this.caption = caption;
        this.isPhotograph = isPhotograph;
        this.width = width;
        this.height = height;
    }

    public Language getLanguage() {
        return language;
    }

    public int getSourceId(){
        return sourceId;
    }

    public String getName() { return name; }

    public String getPageLocation() { return pageLocation; }

    public String getImageLocation() { return imageLocation; }

    public String getCaption() { return caption; }

    public boolean isPhotograph() { return isPhotograph; }

    public int getWidth() { return width; }

    public int getHeight() { return height; }

    // This will probably require downloading a file, so use sparingly
    // Set width to -1 to indicate to use the original size of the image
    // We might not be able to download an image of a particular size automatically
    // In this case, you will need to resize it yourself (after you download the original image)
    public BufferedImage generateImage(int width) throws IOException {
        int extensionIndex = imageLocation.lastIndexOf(".");
        if (extensionIndex < 0) {
            LOG.warn("Image does not have a valid extension");
            return null;
        }

        String extension = imageLocation.substring(extensionIndex + 1).toLowerCase().trim();

        // Get the input stream
        String location = getImageLocation();
        if (width > 0) {
            int index = location.lastIndexOf("/");
            String fileName = "";

            if (index >= 0) {
                fileName = location.substring(index + 1);
            }

            // Append the size path component
            location += "/" + width + "px-" + fileName;

            // SVG Thumbnails get rendered as PNGs
            if (extension.equals("svg")) {
                extension = "png";
                location += ".png";
            }

            // Switch to thumbanil url
            location = location.replace("/commons/", "/commons/thumb/");
        }

        InputStream input = new URL(location).openStream();

        // Check if ImageIO can handle the extension
        if (ImageIO.getImageReadersBySuffix(extension).hasNext()) {
            // Get the image using ImageIO
            return ImageIO.read(input);
        } else if (extension.equals("svg")) {
            // Download and rasterize the SVG file using other means
            return rasterizeSVGFile(input);
        }

        return null;
    }
    public BufferedImage generateImage() throws IOException {
        return generateImage(DEFAULT_IMAGE_WIDTH);
    }

    private BufferedImage rasterizeSVGFile(String string) throws IOException {
        InputStream stream = new ByteArrayInputStream(string.getBytes("UTF-8"));
        return rasterizeSVGFile(stream);
    }

    private BufferedImage rasterizeSVGFile(InputStream stream) throws IOException {
        // Adapted from:
        // http://stackoverflow.com/questions/11435671/how-to-get-a-buffererimage-from-a-svg

        final BufferedImage[] imagePointer = new BufferedImage[1];
        String css = "svg {" +
                "shape-rendering: geometricPrecision;" +
                "text-rendering:  geometricPrecision;" +
                "color-rendering: optimizeQuality;" +
                "image-rendering: optimizeQuality;" +
                "}";
        File cssFile = File.createTempFile("wikibrain-svg-default-override-", ".css");
        FileUtils.writeStringToFile(cssFile, css);

        TranscodingHints transcoderHints = new TranscodingHints();
        transcoderHints.put(ImageTranscoder.KEY_XML_PARSER_VALIDATING, Boolean.FALSE);
        transcoderHints.put(ImageTranscoder.KEY_DOM_IMPLEMENTATION, SVGDOMImplementation.getDOMImplementation());
        transcoderHints.put(ImageTranscoder.KEY_DOCUMENT_ELEMENT_NAMESPACE_URI, SVGConstants.SVG_NAMESPACE_URI);
        transcoderHints.put(ImageTranscoder.KEY_DOCUMENT_ELEMENT, "svg");
        transcoderHints.put(ImageTranscoder.KEY_USER_STYLESHEET_URI, cssFile.toURI().toString());

        try {
            TranscoderInput input = new TranscoderInput(stream);
            ImageTranscoder transcoder = new ImageTranscoder() {
                @Override
                public BufferedImage createImage(int w, int h) {
                    return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                }

                @Override
                public void writeImage(BufferedImage bufferedImage, TranscoderOutput transcoderOutput) throws TranscoderException {
                    imagePointer[0] = bufferedImage;
                }
            };
            transcoder.setTranscodingHints(transcoderHints);
            transcoder.transcode(input, null);
        } catch (TranscoderException e) {
            throw new IOException("Unable to rasterize svg stream");
        } finally {
            cssFile.delete();
        }

        return imagePointer[0];
    }

    // Creates a PNG Base64 String to display on a webpage
    public String generateBase64String(int width) throws IOException {
        BufferedImage image;
        try {
            image = generateImage(width);
        } catch (FileNotFoundException e) {
            image = generateImage();

            // Resize the image manually
            int height = (int)((float)image.getHeight() * (float)width / (float)image.getWidth());
            Image temp = image.getScaledInstance(width, height, Image.SCALE_SMOOTH);
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);

            Graphics2D g2d = result.createGraphics();
            g2d.drawImage(temp, 0, 0, null);
            g2d.dispose();

            image = result;
        }

        if (image == null) {
            return null;
        }

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", byteStream);
        byte[] bytes = Base64.encodeBase64(byteStream.toByteArray());
        return new String(bytes);
    }
    public String generateBase64String() throws IOException {
        return generateBase64String(DEFAULT_IMAGE_WIDTH);
    }

    @Override
    public String toString() {
        return "RawLink{" +
                "language=" + language +
                ", sourceId=" + sourceId +
                ", name=" + name +
                ", pageLocation=" + pageLocation +
                ", imageLocation=" + imageLocation +
                ", caption=" + caption +
                "}";
    }

    @Override
    public int hashCode() {
        if (imageLocation != null) {
            return language.hashCode() ^ sourceId;
        } else {
            return language.hashCode() ^ sourceId ^ imageLocation.hashCode();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof RawImage) {
            RawImage r = (RawImage)obj;
            return r.language == language && r.sourceId == sourceId && r.name == name && r.pageLocation == pageLocation
                    && r.caption == caption && (r.imageLocation == null || imageLocation == null || r.imageLocation == imageLocation);
        }

        return super.equals(obj);
    }
}
