package org.wikibrain.webapi.LogConverter;


import org.apache.commons.collections.IteratorUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.RawImageDao;
import org.wikibrain.core.model.LocalPage;
import org.wikibrain.sr.SRMetric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Created by Josh on 5/5/16.
 */
public class ImageData {
    public static final Logger LOG = LoggerFactory.getLogger(ImageData.class);
    static public class ImageId {
        public int experimentId;
        public int imageIndex;

        public ImageId(int experimentId, int imageIndex) {
            this.experimentId = experimentId;
            this.imageIndex = imageIndex;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ImageId) {
                return experimentId == ((ImageId)obj).experimentId && imageIndex == ((ImageId)obj).imageIndex;
            }

            return false;
        }

        @Override
        public int hashCode() {
            return experimentId * 31 + imageIndex;
        }
    }


    public SRMetric srMetric;
    public RawImageDao riDao;
    public ImageId id;
    ImageData(SRMetric srMetric, RawImageDao riDao, ImageId id) {
        this.srMetric = srMetric;
        this.riDao = riDao;
        this.id = id;
    }

    public List<Integer> rank = new ArrayList<Integer>();
    public List<String>  rankerId = new ArrayList<String>();
    public List<Boolean> questionValidation = new ArrayList<Boolean>();
    public HashMap<String, Integer> validationResponse = new HashMap<String, Integer>();

    void addRankData(int rank, String rankerId, boolean passedQuestionValidation) {
        this.rank.add(rank);
        this.rankerId.add(rankerId);
        questionValidation.add(passedQuestionValidation);
    }

    void addValidationData(int rank, String rankerId) {
        if (validationResponse.containsKey(rankerId)) {
            LOG.warn("Person had validation image twice?!?!?");
        }

        validationResponse.put(rankerId, rank);
    }

    List<String> csvData(String articleText, JSONObject imageData) throws DaoException {
        List<String> data = new ArrayList<String>();
        data.add(id.experimentId + "");
        data.add(id.experimentId + "-" + id.imageIndex);
        data.add(articleText);

        String pageTitle = "";
        String caption = "";

        JSONObject debug = new JSONObject(imageData.getJSONArray("images").getJSONObject(0).getString("debug"));
        if (imageData.getString("title").equals("Map")) {
            JSONObject refMap = new JSONObject(imageData.getJSONArray("images").getJSONObject(0).getString("refMap"));

            data.add("1");
            data.add("");
            caption = imageData.getJSONArray("images").getJSONObject(0).getString("caption");
            data.add(caption);
            data.add("");
            data.add("");

            data.add(refMap.getDouble("ne-lng") + "");
            data.add(refMap.getDouble("sw-lng") + "");
            data.add(refMap.getDouble("ne-lat") + "");
            data.add(refMap.getDouble("sw-lat") + "");

            data.add(refMap.getJSONArray("styles").toString());
            data.add(refMap.getJSONArray("annotations").toString());
            data.add(refMap.getJSONArray("annotations").length() + "");

        } else {
            data.add("0");
            pageTitle = imageData.getString("title");
            data.add(pageTitle);
            caption = imageData.getJSONArray("images").getJSONObject(0).getString("caption");
            data.add(caption);
            String url = imageData.getJSONArray("images").getJSONObject(0).getString("url");
            data.add(url);
            int index = url.lastIndexOf("/");
            if (index >= 0) {
                data.add("https://commons.wikimedia.org/wiki/File:" + url.substring(index + 1));
            } else {
                data.add("");
            }

            data.add("");
            data.add("");
            data.add("");
            data.add("");

            data.add("");
            data.add("");
            data.add("");

        }

        double avgQuestion = 0.0;

        List<Double> rank = new ArrayList<Double>();
        List<Double> normalizedRank = new ArrayList<Double>();
        List<Double> validatedRank = new ArrayList<Double>();
        List<Double> validatedNormalizedRank = new ArrayList<Double>();

        JSONArray questionArray = new JSONArray();
        JSONArray hadValidationImage = new JSONArray();
        JSONArray validationImageResponse = new JSONArray();
        JSONArray passedValidationImage = new JSONArray();
        JSONArray deltaValidationImage = new JSONArray();

        for (int i = 0; i < this.rank.size(); i++) {
            int imgRank = this.rank.get(i);
            rank.add((double)imgRank);
            normalizedRank.add((double)imgRank / 3.0);

            int passedQuestionValidation = questionValidation.get(i) ? 1 : 0;
            questionArray.put(passedQuestionValidation);
            avgQuestion += passedQuestionValidation;

            if (questionValidation.get(i)) {
                validatedRank.add((double)imgRank);
                validatedNormalizedRank.add((double)imgRank);
            }

            if (validationResponse.containsKey(rankerId.get(i))) {
                int answer = validationResponse.get(rankerId.get(i));
                hadValidationImage.put(1);
                validationImageResponse.put(answer);
                passedValidationImage.put(answer == imgRank ? 1 : 0);
                deltaValidationImage.put(answer - imgRank);
            } else {
                hadValidationImage.put("");
                validationImageResponse.put("");
                passedValidationImage.put("");
                deltaValidationImage.put("");
            }
        }

        avgQuestion /= (double)rank.size();

        data.add(Stats.toString(rank));
        data.add(questionArray.toString());

        data.add(hadValidationImage.toString());
        data.add(validationImageResponse.toString());
        data.add(passedValidationImage.toString());
        data.add(deltaValidationImage.toString());

        data.add(Stats.mean(rank) + "");
        data.add(Stats.median(rank) + "");
        data.add(Stats.variance(rank) + "");
        data.add(Stats.mean(normalizedRank) + "");
        data.add(Stats.variance(normalizedRank) + "");
        data.add(avgQuestion + "");

        data.add(Stats.mean(validatedRank) + "");
        data.add(Stats.variance(validatedRank) + "");
        data.add(Stats.mean(validatedNormalizedRank) + "");
        data.add(Stats.variance(validatedNormalizedRank) + "");

        data.add(debug.getString("method"));
        data.add(debug.has("score") ? debug.getString("score") : "");

        if (pageTitle.length() <= 0) {
            data.add(-1.0 + "");
        } else {
            data.add(srMetric.similarity(articleText, pageTitle, false).getScore() + "");
        }
        data.add(srMetric.similarity(articleText, caption, false).getScore() + "");

        if (debug.has("locations")) {
            JSONArray locations = new JSONArray(debug.getString("locations"));
            data.add(locations.toString());

            List<Double> srValues = new ArrayList<Double>();
            for (int i = 0; i < locations.length(); i++) {
                String loc = locations.getString(i);
                srValues.add(srMetric.similarity(articleText, loc, false).getScore());
            }

            data.add(Stats.toString(srValues));
            data.add(Stats.max(srValues) + "");
            data.add(Stats.mean(srValues) + "");
            data.add(Stats.median(srValues) + "");

            data.add("");
            data.add("");
            data.add("");
            data.add("");
            data.add("");
        } else {
            data.add("");
            data.add("");
            data.add("");
            data.add("");
            data.add("");

            JSONArray pageTitles = new JSONArray();
            List<Double> pageSr  = new ArrayList<Double>();

            String url = imageData.getJSONArray("images").getJSONObject(0).getString("url");
            int index = url.lastIndexOf("/");
            if (index >= 0) {
                String title = url.substring(index + 1);
                Iterator<LocalPage> pages = riDao.pagesWithImage(riDao.getImage(title), srMetric.getLanguage());
                while (pages.hasNext()) {
                    String page = pages.next().getTitle().getCanonicalTitle();
                    pageTitles.put(page);
                    pageSr.add(srMetric.similarity(articleText, page, false).getScore());
                }
            }
            if (pageSr.size() > 0) {
                data.add(pageTitles.toString());
                data.add(Stats.toString(pageSr));
                data.add(Stats.max(pageSr) + "");
                data.add(Stats.mean(pageSr) + "");
                data.add(Stats.median(pageSr) + "");
            } else {
                data.add("");
                data.add("");
                data.add("");
                data.add("");
                data.add("");
            }
        }

        return data;
    }

    static List<String> headerData() {
        List<String> header = new ArrayList<String>();
        header.add("id");
        header.add("experiment_id");
        header.add("article_text");

        header.add("is_map");
        header.add("origin_article_title");
        header.add("image_caption");
        header.add("image_url");
        header.add("commons_url");

        header.add("map_ne_lng");
        header.add("map_sw_lng");
        header.add("map_ne_lat");
        header.add("map_sw_lat");

        header.add("map_styles");
        header.add("map_annotations");
        header.add("map_annotations_count");

        header.add("rank");
        header.add("validation_question_correct");

        header.add("had_duplicate_image_validation");
        header.add("duplicate_image_validation_rank");
        header.add("passed_duplicate_image_validation");
        header.add("duplicate_image_delta");

        header.add("avg_rank");
        header.add("median_rank");
        header.add("rank_var");
        header.add("avg_normalized_rank");
        header.add("normalized_rank_var");
        header.add("avg_validation_question_correct");

        header.add("avg_rank_validated");
        header.add("rank_validated_var");
        header.add("avg_normalized_rank_validated");
        header.add("normalized_rank_validated_var");

        header.add("generation_method");
        header.add("generation_score");

        header.add("article_title-text_esa");
        header.add("caption-text_esa");

        header.add("ref_map_locations");
        header.add("locations-article_text_esa");
        header.add("max_locations-article_text_esa");
        header.add("avg_locations-article_text_esa");
        header.add("median_locations-article_text_esa");

        header.add("all_article_title");
        header.add("article_title-article_text_esa");
        header.add("max_article_title-article_text_esa");
        header.add("avg_article_title-article_text_esa");
        header.add("median_article_title-article_text_esa");


        return header;
    }
}
