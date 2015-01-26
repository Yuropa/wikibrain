package org.wikibrain.atlasify;

import javax.validation.constraints.Min;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.google.gson.JsonParser;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONObject;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.Configurator;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.cmd.EnvBuilder;
import org.wikibrain.core.dao.DaoException;
import org.wikibrain.core.dao.LocalLinkDao;
import org.wikibrain.core.dao.LocalPageDao;
//import org.wikibrain.core.jooq.tables.LocalPage;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.lang.LanguageSet;
import org.wikibrain.core.lang.LocalId;
import org.wikibrain.core.model.NameSpace;
import org.wikibrain.core.model.Title;
import org.wikibrain.core.model.LocalPage;
import org.wikibrain.lucene.LuceneSearcher;
import org.wikibrain.phrases.LucenePhraseAnalyzer;
import org.wikibrain.phrases.PhraseAnalyzer;
import org.wikibrain.sr.SRMetric;
import org.wikibrain.sr.SRResult;
import sun.net.www.content.text.plain;

import java.awt.Color;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.Base64;

import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import org.apache.http.client.ResponseHandler;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;


// The Java class will be hosted at the URI path "/helloworld"
@Path("/wikibrain")
public class AtlasifyResource {

    private static class AtlasifyQuery{
        private String keyword;
        private String[] input;

        public AtlasifyQuery(){

        }

        public AtlasifyQuery(String keyword, String[] input){
            this.keyword = keyword;
            this.input = input;
        }

        public AtlasifyQuery(String keyword, List<String> input){
            this.keyword = keyword;
            this.input = input.toArray(new String[input.size()]);
        }

        public String getKeyword(){
            return keyword;
        }

        public String[] getInput(){
            return input;
        }

    }

    private static SRMetric sr = null;
    private static PhraseAnalyzer pa = null;
    private static LocalPageDao lpDao = null;
    private static LocalPageAutocompleteSqlDao lpaDao = null;
    private static LocalLinkDao llDao = null;

    private static void wikibrainSRinit(){

        try {
            Env env = new EnvBuilder().build();
            Configurator conf = env.getConfigurator();
            lpDao = conf.get(LocalPageDao.class);
            lpaDao = conf.get(LocalPageAutocompleteSqlDao.class);
            llDao = conf.get(LocalLinkDao.class);

            Language simple = Language.getByLangCode("simple");

            pa = conf.get(PhraseAnalyzer.class, "lucene");

            sr = conf.get(
                    SRMetric.class, "ensemble",
                    "language", "simple");

        } catch (ConfigurationException e) {
            System.out.println("Configuration Exception: "+e.getMessage());
        } catch (Exception e) {
            System.out.println("Exception: "+e.getMessage());
        }

    }

    private static LocalId wikibrainPhaseResolution(String title) {
        Language language = Language.EN;
        try {
            LinkedHashMap<LocalId, Float> resolution = pa.resolve(language, title, 1);
            for (LocalId p : resolution.keySet()) {
                return p;
            }
        } catch (Exception e) {
            System.out.println("Exception: "+e.getMessage());
        }
        return  null;
    }

    private static Map<LocalId, Double> accessNorthwesternAPI(LocalId id) throws Exception {
        Language language = id.getLanguage();
        String url = "http://downey-n2.cs.northwestern.edu:8080/wikisr/sr/sID/" + id.getId() + "/langID/" + language.getId();
        InputStream inputStream = new URL(url).openStream();

        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, Charset.forName("UTF-8")));
        StringBuilder stringBuilder = new StringBuilder();
        int currentChar;
        while ((currentChar = bufferedReader.read()) != -1) {
            stringBuilder.append((char) currentChar);
        }

        JSONObject jsonObject = new JSONObject(stringBuilder.toString());
        JSONArray jsonArray = jsonObject.getJSONArray("result");
        Map<LocalId, Double> result = new HashMap<LocalId, Double>();
        int length = jsonArray.length();

        for (int i = 0; i < length; i++) {
            JSONObject pageSRPair = jsonArray.getJSONObject(i);
            LocalId page = new LocalId(language, pageSRPair.getInt("wikiPageId"));
            Double sr = new Double(pageSRPair.getDouble("srMeasure"));
            result.put(page, sr);
        }

        return result;
    }

    @POST
    @Path("/autocomplete")
    @Consumes("application/json")
    @Produces("text/plain")

    public Response autocompleteSearch(AtlasifyQuery query) throws Exception {
        if (pa == null) {
            wikibrainSRinit();
        }

        Language language = Language.SIMPLE;
        Map<String, String> autocompleteMap = new HashMap<String, String>();
        try {
            int i = 0;
            /* Phrase Analyzer
            LinkedHashMap<LocalId, Float> resolution = pa.resolve(language, query.getKeyword(), 100);
            for (LocalId p : resolution.keySet()) {
                org.wikibrain.core.model.LocalPage page = lpDao.getById(p);
                autocompleteMap.put(i + "", page.getTitle().getCanonicalTitle());
                i++;
            } */

            /* Page Titles that being/contain search term
            Title title = new Title(query.getKeyword(), language);
            List<LocalPage> similarPages = lpaDao.getBySimilarTitle(title, NameSpace.ARTICLE, llDao);

            for (LocalPage p : similarPages) {
                autocompleteMap.put(i + "", p.getTitle().getCanonicalTitle());
                i++;
            } */

            /* Bing */
            String bingAccountKey = "Y+KqEsFSCzEzNB85dTXJXnWc7U4cSUduZsUJ3pKrQfs";
            byte[] bingAccountKeyBytes = Base64.getEncoder().encode((bingAccountKey + ":" + bingAccountKey).getBytes());
            String bingAccountKeyEncoded = new String(bingAccountKeyBytes);

            String bingQuery = query.getKeyword();
            URL bingQueryurl = new URL("https://api.datamarket.azure.com/Bing/SearchWeb/v1/Web?Query=%27"+java.net.URLEncoder.encode(bingQuery, "UTF-8")+"%20site%3Aen.wikipedia.org%27&$top=50&$format=json");

            HttpURLConnection connection = (HttpURLConnection)bingQueryurl.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Basic " + bingAccountKeyEncoded);
            connection.setRequestProperty("Accept", "application/json");
            BufferedReader br = new BufferedReader(new InputStreamReader((connection.getInputStream())));

            String output;
            StringBuilder sb = new StringBuilder();
            while ((output = br.readLine()) != null) {
                sb.append(output);
            }

            JSONObject bingResponse = new JSONObject(sb.toString());
            bingResponse = bingResponse.getJSONObject("d");
            JSONArray bingResponses = bingResponse.getJSONArray("results");
            JSONObject response;
            for (int j = 0; j < bingResponses.length() && i < 10; j++) {
                response = bingResponses.getJSONObject(j);
                URL url = new URL(response.getString("Url"));
                String path = url.getPath();
                String title = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
                LocalPage page = new LocalPage(language, 0, "");
                for (LocalId p : pa.resolve(language, title, 1).keySet()) {
                    page = lpDao.getById(p);
                }
                if (page != null && !autocompleteMap.values().contains(page.getTitle().getCanonicalTitle())) {
                    autocompleteMap.put(i + "", page.getTitle().getCanonicalTitle());
                    i++;
                }
            }

        } catch (Exception e) {
            autocompleteMap = new HashMap<String, String>();
        }

        return Response.ok(new JSONObject(autocompleteMap).toString()).build();
    }

    // The Java method will process HTTP GET requests
    @GET
    // The Java method will produce content identified by the MIME Media
    // type "text/plain"
    @Path("/SR/keyword={keyword}&feature=[{input}]")
    @Consumes("text/plain")
    @Produces("text/plain")
    public Response getClichedMessage(@PathParam("keyword") String keyword, @PathParam("input") String data) throws  DaoException{
        if(sr == null){
            wikibrainSRinit();
        }
        String[] features = data.split(",");
        Map<String, String> srMap = new HashMap<String, String>();
        for(int i = 0; i < features.length; i++){
            srMap.put(features[i].toString(), getColorStringFromSR(sr.similarity(keyword, features[i].toString(), false).getScore()));
        }
        return Response.ok(new JSONObject(srMap).toString()).header("Access-Control-Allow-Origin", "*").build();
    }
/*
    @POST
    @Path("/send")
    @Produces("text/plain")
    public Response nullResponse () {
        return Response.ok("success").build();
    }
*/

    static private boolean useNorthWesternAPI = false;

    @POST
    @Path("/send")
    @Consumes("application/json")
    @Produces("text/plain")

    public Response consumeJSON (AtlasifyQuery query) throws DaoException{
        if(sr == null){
            wikibrainSRinit();
        }
        String[] features = query.getInput();
        Map<String, String> srMap = new HashMap<String, String>();

        if (useNorthWesternAPI) {

            LocalId queryID = wikibrainPhaseResolution(query.getKeyword());
            // LocalId queryID = new LocalId(Language.EN, 19908980);
            try {
                Map<LocalId, Double> srValues = accessNorthwesternAPI(queryID);

                for (int i = 0; i < features.length; i++) {
                    LocalId featureID = wikibrainPhaseResolution(features[i].toString());
                    String color = getColorStringFromSR(srValues.get(featureID));
                    srMap.put(features[i].toString(), color);
                }
            } catch (Exception e) {
                // There was an error, so we will load empty values
                String color = "#ffffff";
                for (int i = 0; i < features.length; i++) {
                    srMap.put(features[i].toString(), color);
                }
            }
        } else {

            for (int i = 0; i < features.length; i++) {
                String color = "#ffffff";
                try {

                    color = getColorStringFromSR(sr.similarity(query.getKeyword(), features[i].toString(), false).getScore());
                } catch (Exception e) {
                    //do nothing
                }

                srMap.put(features[i].toString(), color);
            }
        }

        return Response.ok(new JSONObject(srMap).toString()).build();
    }

    private String getColorStringFromSR(double SR){
        if(SR < 0.2873)
            return "#ffffff";
        if(SR < 0.3651)
            return "#f7fcf5";
        if(SR < 0.4095)
            return "#e5f5e0";
        if(SR < 0.4654)
            return "#c7e9c0";
        if(SR < 0.5072)
            return "#a1d99b";
        if(SR < 0.5670)
            return "#74c476";
        if(SR < 0.6137)
            return "#41ab5d";
        if(SR < 0.6809)
            return "#238b45";
        if(SR < 0.7345)
            return "#006d2c";
        if(SR < 0.7942)
            return "#00441b";
        return "#002000";
    }
}
