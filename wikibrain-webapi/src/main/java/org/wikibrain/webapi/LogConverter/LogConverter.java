package org.wikibrain.webapi.LogConverter;


import au.com.bytecode.opencsv.CSVWriter;
import org.apache.commons.cli.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.apache.commons.math3.linear.BlockRealMatrix;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.DefaultOptionBuilder;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.cmd.EnvBuilder;
import org.wikibrain.core.dao.RawImageDao;
import org.wikibrain.sr.SRMetric;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.util.*;

/**
 * Created by Josh on 4/29/16.
 */
public class LogConverter {
    public static final Logger LOG = LoggerFactory.getLogger(LogConverter.class);
    public String inputFile;
    public String outputDirectory;
    public String directory;
    public SRMetric esaMetric;
    public RawImageDao riDao;

    public Map<String, List<LogItem>> foundLogs;

    LogConverter(Env env, String inputFile, String outputDirectory, String directory) throws ConfigurationException {
        this.inputFile = inputFile;
        this.outputDirectory = outputDirectory;
        this.directory = directory;

        riDao = env.getConfigurator().get(RawImageDao.class);
        foundLogs = new HashMap<String, List<LogItem>>();
        esaMetric = env.getConfigurator().get(SRMetric.class, "ESA", "language", env.getDefaultLanguage().getLangCode());
    }

    class LogItem {
        public String type;
        public String data;
        public Date date;

        LogItem(String type, String data, String date) {
            this.type = type.trim();
            this.data = data;
            this.date = new Date();
        }

        public JSONObject getJSON() {
            return new JSONObject(data);
        }

        public boolean isType(String type) {
            return this.type.equals(type);
        }
    }

    String readArticle(int i) {
        try {
            String url = directory + "/" + i + "-text.json";
            File file = new File(url);
            return new JSONObject(FileUtils.readFileToString(file)).getString("content");
        } catch (Exception e) {
            LOG.info(e.getLocalizedMessage());
        }

        return null;
    }

    JSONArray readImages(int i) {
        try {
            String url = directory + "/" + i + ".json";
            File file = new File(url);
            return new JSONObject(FileUtils.readFileToString(file)).getJSONArray("articles");
        } catch (Exception e) {
            LOG.info(e.getLocalizedMessage());
        }

        return null;
    }

    Set<Integer> imageIdsForExperiment(int id) {
        JSONArray images = readImages(id);

        Set<Integer> imageIds = new HashSet<Integer>();
        for (int i = 0; i < images.length(); i++) {
            imageIds.add(i);
        }
        return imageIds;
    }

    int experimentId(List<LogItem> events) {
        for (LogItem item : events) {
            if (item.isType("experiment-id")) {
                return Integer.parseInt(item.data);
            }
        }

        return -1;
    }

    class RawRelevanceData {
        public int index;
        public int relevance;

        RawRelevanceData(int index, int relevance) {
            this.index = index;
            this.relevance = relevance;
        }
    }

    List<RawRelevanceData> imageRelevance(List<LogItem> events) {
        List<RawRelevanceData> result = new ArrayList<RawRelevanceData>();
        Set<Integer> indicies = new HashSet<Integer>();

        // Get the number of images
        for (LogItem item : events) {
            if (!item.isType("image-relevance")) {
                continue;
            }

            indicies.add(item.getJSON().getJSONObject("data").getInt("index"));
        }

        // allocate space
        for (int i = 0; i < indicies.size(); i++) {
            result.add(null);
        }

        // Insert image data
        for (LogItem item : events) {
            if (!item.isType("image-relevance")) {
                continue;
            }

            JSONObject data = item.getJSON();
            int relevance = data.getInt("relevance");
            data = data.getJSONObject("data");
            int index = data.getInt("index");
            int id = new JSONObject(data.getString("image")).getInt("id");

            result.set(index, new RawRelevanceData(id, relevance));
        }

        return result;
    }

    boolean isCompletedSession(List<LogItem> events) {
        for (LogItem item : events) {
            if (item.isType("survey-code")) {
                return true;
            }
        }

        return false;
    }

    ImageData getImageData(Map<ImageData.ImageId, ImageData> map, ImageData.ImageId index) {
        if (!map.containsKey(index)) {
            map.put(index, new ImageData(esaMetric, riDao, index));
        }

        return map.get(index);
    }

    ImageData.ImageId imageIdFromIndex(int experimentId, int imageIndex) {
        return new ImageData.ImageId(experimentId, imageIndex);
    }

    boolean completedQuestionValidation(List<LogItem> events) {
        String correctAnswer = null;
        String userAnswer = null;
        for (LogItem item : events) {
            if (item.isType("answer")) {
                correctAnswer = item.data;
            } else if (item.isType("question-answer")) {
                // When the loop is done, this should have the last answer the user picked
                userAnswer = item.data;
            }
        }

        if (correctAnswer == null || userAnswer == null)
            return false;

        return correctAnswer.trim().equals(userAnswer.trim());
    }

    void parseArticle() {
        try {
            File file = new File(inputFile);
            Map<ImageData.ImageId, ImageData> imageData = new HashMap<ImageData.ImageId, ImageData>();
            Map<String, RaterData> raterData = new HashMap<String, RaterData>();

            CSVParser parser = CSVParser.parse(file, Charset.defaultCharset(), CSVFormat.EXCEL);

            for (CSVRecord csvRecord : parser) {
                String id   = csvRecord.get(0).trim();
                String data = csvRecord.get(3).trim();
                String type = csvRecord.get(1).trim();
                String date = csvRecord.get(4).trim();

                if (!foundLogs.containsKey(id)) {
                    foundLogs.put(id, new ArrayList<LogItem>());
                }

                foundLogs.get(id).add(new LogItem(type, data, date));
            }

            for (String id : foundLogs.keySet()) {
                List<LogItem> items = foundLogs.get(id);

                Collections.sort(items, new Comparator<LogItem>() {
                    public int compare(LogItem o1, LogItem o2) {
                        return o1.date.compareTo(o2.date);
                    }
                });

                int experimentId = experimentId(items);
                if (experimentId < 0) {
                    LOG.warn("Invalid experiment");
                    continue;
                }

                if (!isCompletedSession(items)) {
                    LOG.warn("Incomplete session");
                    continue;
                }

                if (!raterData.containsKey(id)) {
                    raterData.put(id, new RaterData(id));
                }

                RaterData rater = raterData.get(id);
                rater.experimentId = experimentId;

                List<RawRelevanceData> rankedImages = imageRelevance(items);
                boolean passesQuestionValidation = completedQuestionValidation(items);
                Set<Integer> processedImages = new HashSet<Integer>();

                rater.passedValidationQuestion = passesQuestionValidation;

                for (RawRelevanceData relevanceData : rankedImages) {
                    ImageData.ImageId imageId = imageIdFromIndex(experimentId, relevanceData.index);
                    ImageData data = getImageData(imageData, imageId);
                    int relevance = relevanceData.relevance;

                    if (processedImages.contains(imageId.imageIndex)) {
                        // Duplicate validation image
                        data.addValidationData(relevance, id);
                        rater.addValidationImageRank(relevance, data.id.imageIndex);
                        continue;
                    }

                    processedImages.add(imageId.imageIndex);
                    data.addRankData(relevance, id, passesQuestionValidation);
                    rater.addImageRank(relevance, data.id.imageIndex);
                }
            }

            File outputDirectory = new File(this.outputDirectory);
            if (outputDirectory.exists()) {
                FileUtils.deleteDirectory(outputDirectory);
            }
            outputDirectory.mkdir();


            // Write all data to CSV file
            File outputFile = new File(this.outputDirectory + "/images.csv");
            Writer fileWriter = new FileWriter(outputFile);
            CSVWriter writer = new CSVWriter(fileWriter);

            List<String> header = ImageData.headerData();
            writer.writeNext(header.toArray(new String[header.size()]));

            int i = 0;
            for (ImageData data : imageData.values()) {
                ImageData.ImageId id = data.id;
                List<String> lineData = data.csvData(readArticle(id.experimentId), readImages(id.experimentId).getJSONObject(id.imageIndex));
                String[] line = lineData.toArray(new String[lineData.size()]);
                writer.writeNext(line);

                i++;
                LOG.info("Writing entry " + i + " of " + imageData.size());
            }

            writer.close();

            for (RaterData data : raterData.values()) {
                data.sortImageIds();
            }

            List<RaterData> allRaters = new ArrayList<RaterData>(raterData.values());
            for (RaterData data : allRaters) {
                File output = new File(this.outputDirectory + "/article-" + data.experimentId + ".csv");

                Writer fw = new FileWriter(output);
                CSVWriter raterWriter = new CSVWriter(fw);

                List<RaterData> ratersInSameExperiment = new ArrayList<RaterData>();
                for (RaterData d : allRaters) {
                    if (d.experimentId == data.experimentId) {
                        ratersInSameExperiment.add(d);
                    }
                }

                List<String> raterHeader = data.headerData(ratersInSameExperiment);
                raterWriter.writeNext(raterHeader.toArray(new String[raterHeader.size()]));

                List<String> line = data.csvData(ratersInSameExperiment);
                raterWriter.writeNext(line.toArray(new String[line.size()]));

                raterWriter.close();

                LOG.info("Writing experiment " + data.experimentId);
            }
        } catch (Exception e) {
            LOG.error(e.getLocalizedMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws ConfigurationException {
        Options options = new Options();
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("input")
                        .withDescription("Input log file")
                        .create("i"));
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("output")
                        .withDescription("Output directory")
                        .create("o"));
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("directory")
                        .withDescription("Directory of article data")
                        .create("d"));

        EnvBuilder.addStandardOptions(options);

        CommandLineParser parser = new PosixParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println( "Invalid option usage: " + e.getMessage());
            new HelpFormatter().printHelp("DumpLoader", options);
            return;
        }

        Env env = new EnvBuilder(cmd).build();

        if (!cmd.hasOption("input")) {
            LOG.error("No input file");
            return;
        }

        if (!cmd.hasOption("output")) {
            LOG.error("No output directory");
            return;
        }

        if (!cmd.hasOption("directory")) {
            LOG.error("No article directory");
            return;
        }

        LogConverter converter = new LogConverter(env,
                "/export/scratch/comparifact/wikibrain/turk-logs/turk.csv",
                "/export/scratch/comparifact/wikibrain/output",
                "/export/scratch/comparifact/wikibrain/articles");
        converter.parseArticle();
    }
}
