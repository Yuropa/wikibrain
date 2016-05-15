package org.wikibrain.webapi.LogConverter;


import com.sleepycat.je.utilint.Stat;
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
    static public int MAX_RATERS = 4;

    static public int MAX_MAP_STYLES = 4;
    static public int MAX_MAP_LOCATIONS = 10;

    static public int MAX_OTHER_ARTICLES = 20;

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

            Stats.writeJSONListWithPadding(data, refMap.getJSONArray("styles"), MAX_MAP_STYLES);
            // data.add(refMap.getJSONArray("annotations").toString());
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

            Stats.writeListWithPadding(data, null, MAX_MAP_STYLES);
            data.add("");

        }

        double avgQuestion = 0.0;

        List<Double> rank = new ArrayList<Double>();
        List<Double> normalizedRank = new ArrayList<Double>();
        List<Double> validatedRank = new ArrayList<Double>();
        List<Double> validatedNormalizedRank = new ArrayList<Double>();

        List<String> questionArray = new ArrayList<String>();
        List<String> hadValidationImage = new ArrayList<String>();
        List<Integer> validationImageResponse = new ArrayList<Integer>();
        List<String> passedValidationImage = new ArrayList<String>();
        List<Integer> deltaValidationImage = new ArrayList<Integer>();

        for (int i = 0; i < this.rank.size(); i++) {
            int imgRank = this.rank.get(i);
            rank.add((double)imgRank);
            normalizedRank.add((double)imgRank / 3.0);

            if (questionValidation.get(i)) {
                questionArray.add("true");
                avgQuestion ++;
            } else  {
                questionArray.add("false");
            }

            if (questionValidation.get(i)) {
                validatedRank.add((double)imgRank);
                validatedNormalizedRank.add((double)imgRank);
            }

            if (validationResponse.containsKey(rankerId.get(i))) {
                int answer = validationResponse.get(rankerId.get(i));
                hadValidationImage.add("true");
                validationImageResponse.add(answer);
                passedValidationImage.add(answer == imgRank ? "true" : "false");
                deltaValidationImage.add(answer - imgRank);
            } else {
                hadValidationImage.add("false");
                validationImageResponse.add(null);
                passedValidationImage.add(null);
                deltaValidationImage.add(null);
            }
        }

        avgQuestion /= (double)rank.size();

        Stats.writeListWithPadding(data, rank, MAX_RATERS);
        Stats.writeListWithPadding(data, questionArray, MAX_RATERS);

        Stats.writeListWithPadding(data, hadValidationImage, MAX_RATERS);
        Stats.writeListWithPadding(data, validationImageResponse, MAX_RATERS);
        Stats.writeListWithPadding(data, passedValidationImage, MAX_RATERS);
        Stats.writeListWithPadding(data, deltaValidationImage, MAX_RATERS);

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
            data.add("");
        } else {
            data.add(srMetric.similarity(articleText, pageTitle, false).getScore() + "");
        }
        data.add(srMetric.similarity(articleText, caption, false).getScore() + "");

        if (debug.has("locations")) {
            JSONArray locations = new JSONArray(debug.getString("locations"));
            Stats.writeJSONListWithPadding(data, locations, MAX_MAP_LOCATIONS);

            List<Double> srValues = new ArrayList<Double>();
            for (int i = 0; i < locations.length(); i++) {
                String loc = locations.getString(i);
                srValues.add(srMetric.similarity(articleText, loc, false).getScore());
            }

            Stats.writeListWithPadding(data, srValues, MAX_MAP_LOCATIONS);
            data.add(Stats.max(srValues) + "");
            data.add(Stats.mean(srValues) + "");
            data.add(Stats.median(srValues) + "");

            Stats.writeListWithPadding(data, null, MAX_OTHER_ARTICLES);
            Stats.writeListWithPadding(data, null, MAX_OTHER_ARTICLES);
            data.add("");
            data.add("");
            data.add("");
        } else {
            Stats.writeListWithPadding(data, null, MAX_MAP_LOCATIONS);
            Stats.writeListWithPadding(data, null, MAX_MAP_LOCATIONS);
            data.add("");
            data.add("");
            data.add("");

            List<String> pageTitles = new ArrayList<String>();
            List<Double> pageSr  = new ArrayList<Double>();

            String url = imageData.getJSONArray("images").getJSONObject(0).getString("url");
            int index = url.lastIndexOf("/");
            if (index >= 0) {
                String title = url.substring(index + 1);
                try {
                    Iterator<LocalPage> pages = riDao.pagesWithImage(riDao.getImage(title), srMetric.getLanguage());
                    while (pages.hasNext()) {
                        String page = pages.next().getTitle().getCanonicalTitle();
                        pageTitles.add(page);
                        pageSr.add(srMetric.similarity(articleText, page, false).getScore());
                    }
                } catch (Exception e) {
                    LOG.error(e.getLocalizedMessage());
                    e.printStackTrace();
                    LOG.error("Unable to get pages for image " + title);
                }
            }
            if (pageSr.size() > 0) {
                Stats.writeListWithPadding(data, pageTitles, MAX_OTHER_ARTICLES);
                Stats.writeListWithPadding(data, pageSr, MAX_OTHER_ARTICLES);
                data.add(Stats.max(pageSr) + "");
                data.add(Stats.mean(pageSr) + "");
                data.add(Stats.median(pageSr) + "");
            } else {
                Stats.writeListWithPadding(data, null, MAX_OTHER_ARTICLES);
                Stats.writeListWithPadding(data, null, MAX_OTHER_ARTICLES);
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

        Stats.writeHeaderWithPadding(header, "map_style_", MAX_MAP_STYLES);
        // header.add("map_annotations");
        header.add("map_annotations_count");

        Stats.writeHeaderWithPadding(header, "score_", MAX_RATERS);
        Stats.writeHeaderWithPadding(header, "validation_question_correct_", MAX_RATERS);

        Stats.writeHeaderWithPadding(header, "has_duplication_image_", MAX_RATERS);
        Stats.writeHeaderWithPadding(header, "duplicate_image_rank_", MAX_RATERS);
        Stats.writeHeaderWithPadding(header, "passed_duplicate_validation_", MAX_RATERS);
        Stats.writeHeaderWithPadding(header, "duplicate_image_delta_", MAX_RATERS);

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

        Stats.writeHeaderWithPadding(header, "map_loc_", MAX_MAP_LOCATIONS);
        Stats.writeHeaderWithPadding(header, "loc-source_text_esa", MAX_MAP_LOCATIONS);
        header.add("max_locations-article_text_esa");
        header.add("avg_locations-article_text_esa");
        header.add("median_locations-article_text_esa");

        Stats.writeHeaderWithPadding(header, "source_article_", MAX_OTHER_ARTICLES);
        Stats.writeHeaderWithPadding(header, "article_title-source_text_esa_", MAX_OTHER_ARTICLES);

        header.add("max_article_title-article_text_esa");
        header.add("avg_article_title-article_text_esa");
        header.add("median_article_title-article_text_esa");


        return header;
    }
}
