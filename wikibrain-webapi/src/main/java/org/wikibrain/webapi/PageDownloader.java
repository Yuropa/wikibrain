package org.wikibrain.webapi;

import com.google.gdata.util.ServiceException;
import com.google.gdata.util.common.base.Joiner;
import org.json.JSONArray;
import org.json.JSONObject;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson.JacksonFactory;

import com.google.gdata.client.spreadsheet.SpreadsheetService;
import com.google.gdata.client.spreadsheet.FeedURLFactory;

import com.google.gdata.client.spreadsheet.CellQuery;

import com.google.gdata.data.spreadsheet.*;

import com.mdimension.jchronic.Chronic;
import de.l3s.boilerpipe.BoilerpipeExtractor;
import de.l3s.boilerpipe.extractors.CommonExtractors;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by Josh on 6/15/16.
 */
public class PageDownloader {
    private static final Logger LOG = LoggerFactory.getLogger(PageDownloader.class);
    private static final int MAX_CACHE_SIZE = 200;
    private static final Date refreshTime = new Date(0, 0, 0, 2, 0);

    static BoilerpipeExtractor extractor = CommonExtractors.ARTICLE_EXTRACTOR;

    private SpreadsheetService service;
    private FeedURLFactory factory;
    private List<ArticleSection> featuredArticles;
    final private int MaximumNumberOfTrendingArticles = 12;

    final private String masterSpreadsheetName = "Comparifact Featured Articles";

    PageDownloader() throws IOException, GeneralSecurityException, ServiceException, InterruptedException {
        String emailAddress = "152281337822-njvo1usnct105ce311asssgvpelfs6ck@developer.gserviceaccount.com";
        JsonFactory JSON_FACTORY = new JacksonFactory();
        HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        Collection<String> scope = Arrays.asList("http://spreadsheets.google.com/feeds/");
        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(httpTransport)
                .setJsonFactory(JSON_FACTORY)
                .setServiceAccountId(emailAddress)
                .setServiceAccountPrivateKeyFromP12File(new File("Atlasify-UMN-6e5cbb645a7f.p12"))
                .setServiceAccountScopes(scope)
                .build();
        factory = FeedURLFactory.getDefault();
        service = new SpreadsheetService("gdata-sample-spreadhsheetindex");
        service.setOAuth2Credentials(credential);

        Date current = new Date();
        refreshTime.setYear(current.getYear());
        refreshTime.setMonth(current.getMonth());
        refreshTime.setDate(current.getDate());
        if (refreshTime.getTime() < current.getTime()) {
            refreshTime.setDate(current.getDate() + 1);
        }

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    loadSpreadSheetData();
                } catch (Exception e) {
                    System.out.println("Error retrieving new featured article data");
                    e.printStackTrace();
                }
            }
        }, (refreshTime.getTime() - current.getTime())/1000, 24*60*60, TimeUnit.SECONDS);

        loadSpreadSheetData(false);
    }

    private final String ReadabilityAPIKey = "042e3228d910e85f442d5a00e95268ff71ccdd28";
    private final String ReadabilityBaseURL = "https://readability.com/api/content/v1";
    public class Article {
        public String content;
        public String date;
        public String title;
        public String imageURL;
        public String url;

        Article(String content, String date, String title, String imageURL, String url) {
            this.content = content;
            this.date = date;
            this.title = title;
            this.imageURL = imageURL;
            this.url = url;
        }
    }

    private String downloadFile(String location, int timeout) {
        try {
            URL url = new URL(location);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);

            connection.setRequestMethod("GET");

            StringBuilder stringBuilder = new StringBuilder();
            int HttpResult = connection.getResponseCode();
            if (HttpResult == HttpURLConnection.HTTP_OK) {
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"));
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    stringBuilder.append(line + "\n");
                }
                bufferedReader.close();
                return stringBuilder.toString();
            } else {
                LOG.error("Unable to download file " + location + " with response code " + HttpResult);
                return "";
            }
        } catch (Exception e) {
            LOG.error(e.getLocalizedMessage());
            return "";
        }
    }

    private static class NYTimesArticle {
        public String url;
        public String title;
        public Date publishedDate;
        public String leadPharagraph;
        public String imageURL;

        NYTimesArticle(String title, String url, String date, String leadPharagraph, String imageURL) {
            this.url = url;
            this.title = title;
            this.leadPharagraph = leadPharagraph;
            this.imageURL = imageURL;

            try {
                this.publishedDate = DateFormatter.parse(date);
            } catch (Exception e) {

                this.publishedDate = new Date();
            }
        }
    }

    private final String NYTimesAPIKey = "85f96420dacfdf4dc66f8514f39967b2:9:73379282";
    private List<NYTimesArticle> getPossibleNYTimesArticles(List<String> keywords) {
        List<NYTimesArticle> articles = new ArrayList<NYTimesArticle>();
        String searchURL = "http://api.nytimes.com/svc/search/v2/articlesearch.json?q=";

        String keyword = "";
        boolean first = true;
        for (String k : keywords) {
            if (first) {
                first = false;
            } else {
                keyword += "+";
            }

            keyword += k;
        }
        searchURL += keyword.replace(" ", "+");
        searchURL += "&fl=web_url%2Cmultimedia%2Cheadline%2Cpub_date%2Clead_paragraph%2Cprint_page&api-key=" + NYTimesAPIKey;

        String articleJSON = downloadFile(searchURL, 0);

        try {
            JSONObject json = new JSONObject(articleJSON);
            JSONArray jsonArticles = json.getJSONObject("response").getJSONArray("docs");
            for (int i = 0; i < jsonArticles.length(); i++) {
                JSONObject article = jsonArticles.getJSONObject(i);
                try {
                    String title = article.getJSONObject("headline").getString("main");
                    String url = article.getString("web_url");
                    String date = article.getString("pub_date");
                    String leadPharagraph = "";
                    if (!article.isNull("lead_paragraph")) {
                        leadPharagraph = article.getString("lead_paragraph");
                    }

                    String imageURL = "";
                    if (article.has("multimedia")) {
                        JSONArray multimedia = article.getJSONArray("multimedia");
                        for (int j = 0; j < multimedia.length(); j++) {
                            if (multimedia.getJSONObject(j).has("url")) {
                                imageURL = multimedia.getJSONObject(j).getString("url");
                                break;
                            }
                        }
                    }

                    NYTimesArticle nyTimesArticle = new NYTimesArticle(title, url, date, leadPharagraph, imageURL);
                    articles.add(nyTimesArticle);
                } catch (Exception e) {
                    LOG.error(e.getLocalizedMessage());
                }
            }

        } catch (Exception e) {
            LOG.error(e.getLocalizedMessage());
        }

        return articles;
    }

    private Article extractHTMLPageText(String urlString) {
        Article content = new Article("", "", "", "", "");
        content.url = urlString;
        try {
            // Check if the url is a NYTimes URL, then use the NYTimes API (I guess readability doesn't work with it :/
            try {
                URL url = new URL(urlString);
                if (url.getHost().equals("www.nytimes.com")) {
                    // A NYTimes URL
                    String[] components = url.getFile().split("/");
                    if (components.length > 0) {
                        String title = components[components.length - 1];
                        title = title.replaceFirst("\\?(.*)", "");
                        title = title.replace(".html", "");
                        List<NYTimesArticle> articles = getPossibleNYTimesArticles(Arrays.asList(title.split("-")));
                        if (articles.size() > 0) {
                            // Found a valid article
                            NYTimesArticle article = articles.get(0);
                            Date publishDate = article.publishedDate;
                            if (publishDate == null) {
                                publishDate = new Date();
                            }
                            content.date = DateFormatter.format(publishDate);
                            content.content = article.leadPharagraph;
                            content.title = article.title;
                            content.imageURL = article.imageURL;
                            return content;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error(e.getLocalizedMessage());
            }

            String readabilityURL = ReadabilityBaseURL + "/parser?token=" + ReadabilityAPIKey + "&url=" + urlString;
            JSONObject jsonObject = new JSONObject(downloadFile(readabilityURL, 0));
            content.content = extractor.getText(jsonObject.getString("content"));

            Date publishedDate = new Date();
            if (jsonObject.has("date_published") && !jsonObject.isNull("date_published")) {
                try {
                    String dateString = jsonObject.getString("date_published");
                    publishedDate = Chronic.parse(dateString).getBeginCalendar().getTime();
                } catch (Exception e) {
                    LOG.error(e.getLocalizedMessage());
                }
            }
            content.date = DateFormatter.format(publishedDate);
            content.title = jsonObject.getString("title");

            if (jsonObject.has("lead_image_url") && !jsonObject.isNull("lead_image_url")) {
                content.imageURL = jsonObject.getString("lead_image_url");
            } else {
                content.imageURL = "";
            }

        } catch (Exception e) {
            LOG.error(e.getLocalizedMessage());
        }

        return content;
    }

    private Map<String, Integer> pageViewCount = new HashMap<String, Integer>();
    private Map<String, Article> pageCache = new LinkedHashMap<String, Article>(MAX_CACHE_SIZE*10/7, 0.7f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Article> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public Article pageForURL(String url) {
        return pageForURL(url, true);
    }
    public Article pageForURL(String url, boolean incrementViewCount) {
        url = url.trim().toLowerCase();

        Article page = null;
        boolean needsToBuildPage = true;
        synchronized (pageCache) {
            if (pageCache.containsKey(url)) {
                page = pageCache.get(url);
                needsToBuildPage = false;
            }
        }

        if (needsToBuildPage) {
            page = extractHTMLPageText(url);

            synchronized (pageCache) {
                pageCache.put(url, page);
            }
        }

        // Increase page count
        if (incrementViewCount) {
            synchronized (pageViewCount) {
                if (!pageViewCount.containsKey(url)) {
                    pageViewCount.put(url, 0);
                }

                pageViewCount.put(url, pageViewCount.get(url) + 1);
            }
        }

        return page;
    }

    /* Featured Articles */
    private List<SpreadsheetEntry> getSpreadsheetEntries() throws IOException, ServiceException {
        SpreadsheetFeed feed = service.getFeed(
                factory.getSpreadsheetsFeedUrl(), SpreadsheetFeed.class);
        return feed.getEntries();
    }

    private List<WorksheetEntry> getWorksheetEntries(SpreadsheetEntry spreadsheet)
            throws IOException, ServiceException  {
        return spreadsheet.getWorksheets();
    }

    public class ArticleSection {
        ArticleSection (String title, List<Article> articles) {
            this.title = title;
            this.articles = articles;
        }

        private String title;
        private List<Article> articles;

        public String getTitle() {
            return title;
        }

        public List<Article> getArticles() {
            return articles;
        }

        public void addArticle(Article article) {
            getArticles().add(article);
        }
    }
    private List<ArticleSection> getSheadsheetData(WorksheetEntry worksheet)
            throws IOException, ServiceException  {

        // Get the appropriate URL for a cell feed
        URL cellFeedUrl = worksheet.getCellFeedUrl();

        // Create a query for the top row of cells only (1-based)
        CellQuery cellQuery = new CellQuery(cellFeedUrl);

        // Get the cell feed matching the query
        CellFeed cellFeed = service.query(cellQuery, CellFeed.class);

        // Get the cell entries from the feed
        List<CellEntry> cellEntries = cellFeed.getEntries();
        List<ArticleSection> sections = new ArrayList<ArticleSection>();
        for (CellEntry entry : cellEntries) {
            // Get the cell element from the entry
            Cell cell = entry.getCell();
            if (cell.getRow() == 1) {
                continue; // This is the header row, so we don't need to keep it
            }

            if (cell.getCol() == 1) {
                ArticleSection section = new ArticleSection(cell.getValue(), new ArrayList<Article>());
                sections.add(section);
            } else  {
                ArticleSection section = sections.get(sections.size() - 1);
                section.addArticle(new Article("", "", "", "", cell.getValue()));
            }
        }

        return sections;
    }

    private void loadSpreadSheetData() throws Exception {
        loadSpreadSheetData(true);
    }
    private void loadSpreadSheetData(boolean syncTrendingData) throws IOException, ServiceException, InterruptedException  {
        SpreadsheetEntry masterSpreadsheet = null;
        String errorMessage = "";
        for (SpreadsheetEntry spreadsheet : getSpreadsheetEntries()) {
            if (spreadsheet.getTitle().getPlainText().equals(masterSpreadsheetName)) {
                masterSpreadsheet = spreadsheet;
            } else {
                errorMessage += "\tFound spreadsheet \"" + spreadsheet.getTitle().getPlainText() + "\"\n";
            }
        }

        WorksheetEntry worksheet = null;
        if (masterSpreadsheet != null) {
            // We will always use the first sheet, could change later if needed
            worksheet = getWorksheetEntries(masterSpreadsheet).get(0);
            if (worksheet == null) {
                LOG.error("Unable to load worksheet from master spreadsheet");
                return;
            }
        } else {
            LOG.error("Could not find master spreadsheet named \"" + masterSpreadsheetName + "\"\n" + errorMessage);
            return;
        }

        if (syncTrendingData) {
            syncTrendingData(worksheet);
            Thread.sleep(10000);
        }
        syncFeaturedData(worksheet);
    }

    private void syncFeaturedData(WorksheetEntry worksheet) throws IOException, ServiceException  {
        // Download spreadsheet from Google Drive
        System.out.println("BEGIN loading feature article data");
        featuredArticles = getSheadsheetData(worksheet);
        System.out.println("FINISHED loading feature article data");
    }
    public List<ArticleSection> getFeaturedArticles() {
        return featuredArticles;
    }

    private void syncTrendingData(WorksheetEntry worksheet) throws IOException, ServiceException  {
        System.out.println("BEGIN Updating Trending Data");

        class RankedArticle implements Comparable<RankedArticle> {
            int views;
            String url;
            RankedArticle(String url, int views) {
                this.url = url;
                this.views = views;
            }

            @Override
            public int compareTo(RankedArticle o) {
                return Integer.compare(views, o.views);
            }
        }

        List<RankedArticle> trendingArticles = new ArrayList<RankedArticle>();
        synchronized (pageViewCount) {
            for (String url : pageViewCount.keySet()) {
                trendingArticles.add(new RankedArticle(url, pageViewCount.get(url)));
                // Make sure the trending articles are loaded in the cache
                pageForURL(url, false);
            }

            // Clear the previous page counts
            pageViewCount = new HashMap<String, Integer>();
        }

        Collections.sort(trendingArticles);
        Collections.reverse(trendingArticles);

        // Fill in the rending articles to the maximum size with past trending articles if necessary
        List<Article> pastTrendingArticles = new ArrayList<Article>(getSheadsheetData(worksheet).get(0).getArticles());
        while (trendingArticles.size() < MaximumNumberOfTrendingArticles && pastTrendingArticles.size() > 0) {
            Article article = pastTrendingArticles.remove(0);

            boolean foundDuplicate = false;
            for (RankedArticle rankedArticle : trendingArticles) {
                if (rankedArticle.url == article.url) {
                    foundDuplicate = true;
                    break;
                }
            }

            if (foundDuplicate) {
                continue;
            } else {
                trendingArticles.add(new RankedArticle(article.url, 0));
            }
        }

        URL trendingFeedUrl = worksheet.getCellFeedUrl();
        CellQuery trendingQuery = new CellQuery(trendingFeedUrl);
        CellFeed trendingFeed = service.query(trendingQuery, CellFeed.class);
        int savedCells = 0;
        // Write the data to the sheet
        for (CellEntry cellEntry : trendingFeed.getEntries()) {
            Cell cell = cellEntry.getCell();
            if (cell.getRow() != 2) {
                // We only need to update the trending row
                continue;
            }
            if (cell.getCol() == 1) {
                // This should be the header row
                continue;
            }

            // Indexing Begins from 1 and we are skipping the header
            int index = cell.getCol() - 2;
            if (index >= trendingArticles.size()) {
                cellEntry.delete();
                continue;
            }

            // Set the appropriate value
            cellEntry.changeInputValueLocal(trendingArticles.get(index).url);
            cellEntry.update();
            savedCells++;
        }

        // Add in the remaining cells
        while (savedCells < trendingArticles.size()) {
            // Indexing Begins from 1 and we are skipping the header
            int index = savedCells + 2;
            CellEntry cellEntry = new CellEntry(2, index, trendingArticles.get(savedCells).url);
            trendingFeed.insert(cellEntry);
            savedCells++;
        }

        System.out.println("FINISH Updating Trending Data");
    }
}
