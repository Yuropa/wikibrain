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

        JSONArray imagesArray = new JSONArray();
        JSONArray ranksArray  = new JSONArray();

        for (int i = 0; i < imageIds.size(); i++) {
            imagesArray.put(imageIds.get(i));
            ranksArray.put(imageRanks.get(i));
            ranks.add((double)imageRanks.get(i));
        }

        data.add(imagesArray.toString());
        data.add(ranksArray.toString());

        data.add(Stats.mean(ranks) + "");
        data.add(Stats.median(ranks) + "");
        data.add(Stats.variance(ranks) + "");

        data.add(passedValidationQuestion ? "1" : "0");

        JSONArray validationImage = new JSONArray();
        JSONArray validationRank = new JSONArray();
        JSONArray validationDelta = new JSONArray();
        int incorrectValidation = 0;

        for (int i = 0; i < imageIds.size(); i++) {
            int id = imageIds.get(i);
            int rank = imageRanks.get(i);

            if (validationImages.containsKey(id)) {
                validationImage.put(1);
                int vRank = validationImages.get(id);
                validationRank.put(vRank);
                validationDelta.put(vRank - rank);

                if (vRank != rank) {
                    incorrectValidation++;
                }
            } else {
                validationImage.put(0);
                validationRank.put("");
                validationDelta.put("");
            }
        }

        data.add(validationImage.toString());
        data.add(validationRank.toString());
        data.add(validationDelta.toString());
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

    static List<String> headerData(List<RaterData> allRaters) {
        List<String> header = new ArrayList<String>();
        header.add("name");
        header.add("id");
        header.add("images");
        header.add("image_rank");

        header.add("avg_rank");
        header.add("median_rank");
        header.add("rank_var");

        header.add("passed_validation_question");
        // header.add("article_text");

        header.add("duplicate_validation_images");
        header.add("duplication_validation_images_rank");
        header.add("duplication_validation_images_delta");
        header.add("total_incorrect_duplication_validation_images");

        header.add("Rater-Rater Correlation: "); // Padding

        for (RaterData d : allRaters) {
            header.add(d.id);
        }

        return header;
    }
}

