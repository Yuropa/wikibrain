package org.wikibrain.webapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Josh on 6/15/16.
 */
public class DateFormatter {
    private static final Logger LOG = LoggerFactory.getLogger(DateFormatter.class);
    private static ThreadLocal<DateFormat> dateFormat = new ThreadLocal<DateFormat>(){
        @Override
        public DateFormat get() {
            return super.get();
        }

        @Override
        protected DateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd");
        }

        @Override
        public void remove() {
            super.remove();
        }

        @Override
        public void set(DateFormat value) {
            super.set(value);
        }
    };

    // Taken from http://stackoverflow.com/questions/3389348/parse-any-date-in-java
    private static final Map<String, String> DATE_FORMAT_REGEXPS = new HashMap<String, String>() {{
        put("^\\d{4}$", "yyyy");
        put("^\\d{4}-\\d{1,2}$", "yyyy-MM");
    }};

    private static String determineDateFormat(String dateString) {
        for (String regexp : DATE_FORMAT_REGEXPS.keySet()) {
            if (dateString.toLowerCase().matches(regexp)) {
                return DATE_FORMAT_REGEXPS.get(regexp);
            }
        }
        return null; // Unknown format.
    }

    static public Date parse(String s) {
        try {
            if (s.toLowerCase().matches("^\\d{4}-\\d{1,2}-\\d{1,2}$")) {
                return dateFormat.get().parse(s);
            }

            String format = determineDateFormat(s);
            if (format == null) {
                return null;
            }
            return new SimpleDateFormat(format).parse(s);
        } catch (Exception e) {
            LOG.error(e.getLocalizedMessage());
            return new Date();
        }
    }

    static public String format(Date d) {
        return dateFormat.get().format(d);
    }
}
