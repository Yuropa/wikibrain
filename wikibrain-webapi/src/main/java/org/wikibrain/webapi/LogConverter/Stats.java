package org.wikibrain.webapi.LogConverter;


import org.apache.commons.math3.linear.RealMatrix;
import org.json.JSONArray;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by Josh on 5/5/16.
 */
public class Stats {
    static double max(List<Double> numbers) {
        return Collections.max(numbers);
    }

    static <T> String toString(List<T> numbers) {
        JSONArray array = new JSONArray();
        for (T item : numbers) {
            array.put(item);
        }
        return array.toString();
    }

    static double median(List<Double> numbers) {
        numbers = new ArrayList<Double>(numbers);
        Collections.sort(numbers);
        if (numbers.size() == 0) {
            return 0.0;
        }

        if (numbers.size() % 2 == 0) {
            return (numbers.get(numbers.size() / 2) + numbers.get(numbers.size() / 2 - 1)) / 2.0;
        } else {
            return numbers.get(numbers.size() / 2);
        }
    }

    static double mean(List<Double> numbers) {
        if (numbers.size() == 0) {
            return 0.0;
        }

        double result = 0.0;
        for (Double value : numbers) {
            result += value;
        }

        return result / (double)numbers.size();
    }
    static double variance(List<Double> numbers) {
        if (numbers.size() == 0) {
            return 0.0;
        }

        double mean = mean(numbers);
        double variance = 0.0;
        for (Double val : numbers) {
            variance += (val - mean)*(val - mean);
        }

        return variance / (double)numbers.size();
    }

    static String stringFromMatrix(RealMatrix matrix) {
        String result = "";
        DecimalFormat df = new DecimalFormat(" 0.0000;-0.0000");
        for (int i = 0; i < matrix.getRowDimension(); i++) {
            if (i != 0) {
                // result += "\n";
            }

            double row[] = matrix.getRow(i);
            for (int j = 0; j < row.length; j++) {
                if (j != 0) {
                    result += " ";
                }
                result += df.format(row[j]);
            }
        }
        return result;
    }
}

