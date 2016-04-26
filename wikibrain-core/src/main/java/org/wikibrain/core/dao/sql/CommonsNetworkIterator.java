package org.wikibrain.core.dao.sql;

import com.fasterxml.jackson.databind.MappingIterator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.FileUtils;
import org.jooq.tools.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Created by Josh on 4/25/16.
 */
public abstract class CommonsNetworkIterator<T> implements Iterator<T> {
    private Iterator<JsonElement> objects = null;
    private String continueString = null;
    private String start;
    private String end;

    public CommonsNetworkIterator(String start, String end) throws Exception {
        this.start = start;
        this.end = end;
    }

    abstract String buildSearchURL(String start, String end);
    abstract String continueSearchKey();
    abstract JsonArray getQueryResult(JsonObject json);
    abstract T buildObject(JsonObject json);

    protected void performDownload() throws Exception {
        String s = start;
        if (continueString != null) {
            s = continueString;
        }
        String e = end;

        s = URLEncoder.encode(s, "utf-8");
        e = URLEncoder.encode(e, "utf-8");

        String location = buildSearchURL(s, e);
        URL url = new URL(location);
        URLConnection connection = url.openConnection();
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

        StringBuilder builder = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }

        reader.close();

        JsonParser parser = new JsonParser();
        JsonObject result = parser.parse(builder.toString()).getAsJsonObject();

        if (result.has("continue")) {
            continueString = result.getAsJsonObject("continue").get(continueSearchKey()).getAsString();
        } else {
            continueString = null;
        }

        JsonArray query = getQueryResult(result.getAsJsonObject("query"));
        objects = query.iterator();
    }

    @Override
    public T next() {
        if (objects == null || (!objects.hasNext() && continueString != null)) {
            try {
                performDownload();
            } catch (Exception e) {
                throw new NoSuchElementException(e.getLocalizedMessage());
            }
        }

        return buildObject(objects.next().getAsJsonObject());
    }

    @Override
    public boolean hasNext() {
        if (!objects.hasNext()) {
            return continueString != null;
        }

        return true;
    }

    @Override
    public void remove() {
        objects.remove();
    }
}
