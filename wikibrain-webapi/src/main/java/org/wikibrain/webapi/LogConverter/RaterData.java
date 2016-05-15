package org.wikibrain.webapi.LogConverter;


import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Created by Josh on 5/5/16.
 */
public class RaterData {
    public static final Logger LOG = LoggerFactory.getLogger(RaterData.class);
    public String id;
    public int experimentId;
    public boolean passedValidationQuestion;
    public List<Integer> imageIds = new ArrayList<Integer>();
    public List<Integer> imageRanks = new ArrayList<Integer>();

    public HashMap<Integer, Integer> validationImages = new HashMap<Integer, Integer>();

    public RaterData(String id) {
        this.id = id;
    }
    void addImageRank(int rank, int imageId) {
        imageIds.add(imageId);
        imageRanks.add(rank);
    }

    void addValidationImageRank(int rank, int imageId) {
        if (validationImages.containsKey(imageId)) {
            LOG.warn("Duplicate validation image!?!?");
        }

        validationImages.put(imageId, rank);
    }

    void sortImageIds() {
        Map<Integer, Integer> imageRanks = new HashMap<Integer, Integer>();
        for (int i = 0; i < imageIds.size(); i++) {
            imageRanks.put(imageIds.get(i), this.imageRanks.get(i));
        }

        Collections.sort(imageIds);

        for (int i = 0; i < imageIds.size(); i++) {
            this.imageRanks.set(i, imageRanks.get(imageIds.get(i)));
        }
    }

    List<String> csvData(List<RaterData> allRaters) {
        List<String> data = new ArrayList<String>();
        data.add(id);
        data.add(experimentId + "");
        // data.add(readArticle(id.experimentId));

        List<Double> ranks = new ArrayList<Double>();

        for (int i = 0; i < imageIds.size(); i++) {
            ranks.add((double)imageRanks.get(i));
        }

        Stats.writeListWithPadding(data, imageIds, imageIds.size());
        Stats.writeListWithPadding(data, ranks, imageIds.size());

        data.add(Stats.mean(ranks) + "");
        data.add(Stats.median(ranks) + "");
        data.add(Stats.variance(ranks) + "");

        data.add(passedValidationQuestion ? "1" : "0");

        List<String> validationImage = new ArrayList<String>();
        List<Integer> validationRank = new ArrayList<Integer>();
        List<Integer> validationDelta = new ArrayList<Integer>();
        int incorrectValidation = 0;

        for (int i = 0; i < imageIds.size(); i++) {
            int id = imageIds.get(i);
            int rank = imageRanks.get(i);

            if (validationImages.containsKey(id)) {
                validationImage.add("true");
                int vRank = validationImages.get(id);
                validationRank.add(vRank);
                validationDelta.add(vRank - rank);

                if (vRank != rank) {
                    incorrectValidation++;
                }
            } else {
                validationImage.add("false");
                validationRank.add(null);
                validationDelta.add(null);
            }
        }

        Stats.writeListWithPadding(data, validationImage, imageIds.size());
        Stats.writeListWithPadding(data, validationRank, imageIds.size());
        Stats.writeListWithPadding(data, validationDelta, imageIds.size());
        data.add(incorrectValidation + "");

        data.add(""); // Padding

        for (RaterData d : allRaters) {
            double[] x = new double[imageRanks.size()];
            for (int i = 0; i < imageRanks.size(); i++) {
                x[i] = imageRanks.get(i);
            }

            double[] y = new double[d.imageRanks.size()];
            for (int i = 0; i < d.imageRanks.size(); i++) {
                y[i] = d.imageRanks.get(i);
            }

            data.add(new PearsonsCorrelation().correlation(x, y) + "");
        }

        return data;
    }

    List<String> headerData(List<RaterData> allRaters) {
        List<String> header = new ArrayList<String>();
        header.add("name");
        header.add("id");
        Stats.writeHeaderWithPadding(header, "img_", imageIds.size());
        Stats.writeHeaderWithPadding(header, "img_score_", imageIds.size());

        header.add("avg_score");
        header.add("median_score");
        header.add("score_var");

        header.add("passed_validation_question");
        // header.add("article_text");

        Stats.writeHeaderWithPadding(header, "has_duplicate_", imageIds.size());
        Stats.writeHeaderWithPadding(header, "duplicate_score", imageIds.size());
        Stats.writeHeaderWithPadding(header, "duplicate_delta", imageIds.size());
        header.add("total_incorrect_duplication_validation_images");

        header.add("Rater-Rater Correlation: "); // Padding

        for (RaterData d : allRaters) {
            header.add(d.id);
        }

        return header;
    }
}

