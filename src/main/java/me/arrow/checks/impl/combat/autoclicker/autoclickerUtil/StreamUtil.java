package me.arrow.checks.impl.combat.autoclicker.autoclickerUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AtomicDouble;
import me.arrow.utils.custom.Pair;
import me.arrow.utils.customutils.Tuple;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

public class StreamUtil {
    public static <T> Collection<T> filter(Collection<T> data, Predicate<T> filter) {
        List<T> list = new LinkedList<>();
        if (filter != null && !data.isEmpty()) {
            for (T object : data) {
                if (filter.test(object)) {
                    list.add(object);
                }
            }

            return list;
        } else {
            return list;
        }
    }

    public static double getAverageNumberDifference(List<Double> doubles) {
        if (doubles != null && doubles.size() >= 2) {
            double sumDifference = 0.0;

            for (int i = 0; i < doubles.size() - 1; i++) {
                double diff = Math.abs(doubles.get(i + 1) - doubles.get(i));
                sumDifference += diff;
            }

            return sumDifference / (double)(doubles.size() - 1);
        } else {
            return Double.NaN;
        }
    }

    public static double mean(Collection<? extends Number> samples) {
        double sum = 0.0;

        for (Number val : samples) {
            sum += val.doubleValue();
        }

        return sum / (double)samples.size();
    }

    public static double calculateSerialCorrelation(Collection<? extends Number> data) {
        if (data != null && data.size() >= 2) {
            double mean = mean(data);
            double numerator = 0.0;
            double denominator = 0.0;
            Number[] dataArray = data.toArray(new Number[0]);

            for (int i = 0; i < dataArray.length - 1; i++) {
                numerator += (dataArray[i].doubleValue() - mean) * (dataArray[i + 1].doubleValue() - mean);
            }

            for (Number number : dataArray) {
                denominator += Math.pow(number.doubleValue() - mean, 2.0);
            }

            return numerator / denominator;
        } else {
            throw new IllegalArgumentException("Data series must contain at least two elements.");
        }
    }

    public static double giniCoefficient(Collection<? extends Number> data) {
        if (data != null && data.size() >= 2) {
            Number[] dataArray = data.toArray(new Number[0]);
            int n = dataArray.length;
            double[] values = new double[n];

            for (int i = 0; i < n; i++) {
                values[i] = dataArray[i].doubleValue();
            }

            Arrays.sort(values);
            double cumulativeSum = 0.0;
            double cumulativeValuesSum = 0.0;

            for (int i = 0; i < n; i++) {
                cumulativeValuesSum += values[i];
                cumulativeSum += cumulativeValuesSum;
            }

            double mean = cumulativeValuesSum / (double)n;
            return ((double)n + 1.0 - 2.0 * cumulativeSum / cumulativeValuesSum) / (double)n;
        } else {
            throw new IllegalArgumentException("Data series must contain at least two elements.");
        }
    }

    public static double getEntropy(Collection<? extends Number> data) {
        if (data != null && !data.isEmpty()) {
            Map<Double, Integer> frequencyMap = new HashMap<>();

            for (Number number : data) {
                double value = number.doubleValue();
                frequencyMap.put(value, frequencyMap.getOrDefault(value, 0) + 1);
            }

            double entropy = 0.0;
            double total = (double)data.size();

            for (Entry<Double, Integer> entry : frequencyMap.entrySet()) {
                double probability = (double)entry.getValue().intValue() / total;
                entropy -= probability * (Math.log(probability) / Math.log(2.0));
            }

            return entropy;
        } else {
            throw new IllegalArgumentException("Data series must not be empty.");
        }
    }

    public static double getGrid(List<Float> entry) {
        double average = 0.0;
        double min = 0.0;
        double max = 0.0;
        Iterator var7 = entry.iterator();

        while (var7.hasNext()) {
            double number = (double)((Float)var7.next()).floatValue();
            if (number < min) {
                min = number;
            }

            if (number > max) {
                max = number;
            }

            average += number;
        }

        average /= (double)entry.size();
        return max - average - min;
    }

    public static double getGridDouble(Collection<Double> entry) {
        double average = 0.0;
        double min = 0.0;
        double max = 0.0;

        for (double number : entry) {
            if (number < min) {
                min = number;
            }

            if (number > max) {
                max = number;
            }

            average += number;
        }

        average /= (double)entry.size();
        return max - average - min;
    }

    public static <T extends Number> T getModeV2(Collection<T> collect) {
        Map<T, Integer> repeated = new HashMap<>();
        collect.forEach(val -> {
            int number = repeated.getOrDefault(val, 0);
            repeated.put((T)val, number + 1);
        });
        return (T)repeated.keySet()
                .stream()
                .map(key -> new Tuple<>(key, repeated.get(key)))
                .max(Comparator.comparing(tup -> (Integer)tup.two, Comparator.naturalOrder()))
                .orElseThrow(NullPointerException::new)
                .one;
    }

    public static Number getMode(Collection<? extends Number> samples) {
        Map<Number, Integer> frequencies = new HashMap<>();
        samples.forEach(i -> frequencies.put(i, frequencies.getOrDefault(i, 0) + 1));
        Number mode = null;
        int highest = 0;

        for (Entry<Number, Integer> entry : frequencies.entrySet()) {
            if (entry.getValue() > highest) {
                mode = entry.getKey();
                highest = entry.getValue();
            }
        }

        return mode;
    }

    public static double getCPS(Collection<? extends Number> values) {
        return 20.0 / getAverage(values);
    }

    public static int getDuplicates(Collection<? extends Number> collection) {
        return collection.size() - getDistinct(collection);
    }

    public static double getKurtosis(Collection<? extends Number> values) {
        double n = (double)values.size();
        if (n < 3.0) {
            return Double.NaN;
        } else {
            double average = getAverage(values);
            double stDev = getStandardDeviation(values);
            AtomicDouble accum = new AtomicDouble(0.0);
            values.forEach(delay -> accum.getAndAdd(Math.pow(delay.doubleValue() - average, 4.0)));
            return n * (n + 1.0) / ((n - 1.0) * (n - 2.0) * (n - 3.0)) * (accum.get() / Math.pow(stDev, 4.0))
                    - 3.0 * Math.pow(n - 1.0, 2.0) / ((n - 2.0) * (n - 3.0));
        }
    }

    public static synchronized double getAverage(Collection<? extends Number> values) {
        return values.stream().mapToDouble(Number::doubleValue).average().orElse(0.0);
    }

    public static double getAverageV(Collection<? extends Number> data) {
        if (data != null && !data.isEmpty()) {
            double sum = 0.0;

            for (Number number : data) {
                sum += number.doubleValue();
            }

            return sum / (double)data.size();
        } else {
            return 0.0;
        }
    }

    public static double getSkewness(Collection<? extends Number> data) {
        double sum = 0.0;
        int count = 0;
        List<Double> numbers = Lists.newArrayList();

        for (Number number : data) {
            sum += number.doubleValue();
            count++;
            numbers.add(number.doubleValue());
        }

        Collections.sort(numbers);
        double mean = sum / (double)count;
        double median = count % 2 != 0 ? numbers.get(count / 2) : (numbers.get((count - 1) / 2) + numbers.get(count / 2)) / 2.0;
        double variance = getVariance(data);
        return 3.0 * (mean - median) / variance;
    }

    public static double deviationSquared(Iterable<? extends Number> iterable) {
        double n = 0.0;
        int n2 = 0;

        for (Number anIterable : iterable) {
            n += anIterable.doubleValue();
            n2++;
        }

        double n3 = n / (double)n2;
        double n4 = 0.0;

        for (Number anIterable : iterable) {
            n4 += Math.pow(anIterable.doubleValue() - n3, 2.0);
        }

        return n4 == 0.0 ? 0.0 : n4 / (double)(n2 - 1);
    }

    public static double[] getMagnitudes(Collection<? extends Number> data) {
        int n = data.size();
        double[] dataArray = new double[n];
        int i = 0;

        for (Number number : data) {
            dataArray[i++] = number.doubleValue();
        }

        int paddedSize = 1;

        while (paddedSize < n) {
            paddedSize <<= 1;
        }

        double[] paddedArray = new double[paddedSize];
        System.arraycopy(dataArray, 0, paddedArray, 0, n);
        FastFourierTransformer transformer = new FastFourierTransformer(DftNormalization.STANDARD);
        Complex[] fftResultComplex = transformer.transform(paddedArray, TransformType.FORWARD);
        double[] magnitudes = new double[paddedSize / 2];

        for (int var9 = 0; var9 < paddedSize / 2; var9++) {
            magnitudes[var9] = fftResultComplex[var9].abs();
        }

        return magnitudes;
    }

    public static double getAverageMagnitude(double[] magnitudes) {
        double sum = 0.0;

        for (double magnitude : magnitudes) {
            sum += magnitude;
        }

        return sum / (double)magnitudes.length;
    }

    public static double getMedian(Iterable<? extends Number> iterable) {
        List<Double> data = new ArrayList<>();

        for (Number number : iterable) {
            data.add(number.doubleValue());
        }

        return getMedian(data);
    }

    public static double getDeviation(Collection<? extends Number> nums) {
        return nums.isEmpty() ? 0.0 : Math.sqrt(getVariance(nums) / (double)(nums.size() - 1));
    }

    public static double getVariance(Collection<? extends Number> data) {
        if (data.isEmpty()) {
            return 0.0;
        } else {
            int count = 0;
            double sum = 0.0;
            double variance = 0.0;

            for (Number number : data) {
                sum += number.doubleValue();
                count++;
            }

            double average = sum / (double)count;

            for (Number number : data) {
                variance += Math.pow(number.doubleValue() - average, 2.0);
            }

            return variance;
        }
    }

    public static int getDistinct(Collection<? extends Number> collection) {
        return collection.isEmpty() ? 0 : new HashSet<>(collection).size();
    }

    public static double getMaximumDouble(Collection<Double> nums) {
        if (nums.isEmpty()) {
            return 0.0;
        } else {
            double max = Double.MIN_VALUE;

            for (double val : nums) {
                if (val > max) {
                    max = val;
                }
            }

            return max;
        }
    }

    public static double getStandardDeviation(Collection<? extends Number> values) {
        double average = getAverage(values);
        AtomicDouble variance = new AtomicDouble(0.0);
        values.forEach(delay -> variance.getAndAdd(Math.pow(delay.doubleValue() - average, 2.0)));
        return Math.sqrt(variance.get() / (double)values.size());
    }

    public static double getMedian(List<Double> data) {
        if (data.size() > 1) {
            return data.size() % 2 == 0 ? (data.get(data.size() / 2) + data.get(data.size() / 2 - 1)) / 2.0 : data.get(Math.round((float)data.size() / 2.0F));
        } else {
            return 0.0;
        }
    }

    public static Pair<List<Double>, List<Double>> getOutliersV2(Collection<? extends Number> collection) {
        List<Double> values = new ArrayList<>();

        for (Number number : collection) {
            values.add(number.doubleValue());
        }

        double q1 = getMedian(values.subList(0, values.size() / 2));
        double q3 = getMedian(values.subList(values.size() / 2, values.size()));
        double iqr = Math.abs(q1 - q3);
        double lowThreshold = q1 - 1.5 * iqr;
        double highThreshold = q3 + 1.5 * iqr;
        Pair<List<Double>, List<Double>> tuple = new Pair<>(new ArrayList<>(), new ArrayList<>());

        for (Double value : values) {
            if (value < lowThreshold) {
                tuple.getKey().add(value);
            } else if (value > highThreshold) {
                tuple.getValue().add(value);
            }
        }

        return tuple;
    }

    public static Tuple<List<Double>, List<Double>> getOutliers(Collection<? extends Number> collection) {
        List<Double> values = new ArrayList<>();

        for (Number number : collection) {
            values.add(number.doubleValue());
        }

        if (values.size() < 4) {
            return new Tuple<>(new ArrayList<>(), new ArrayList<>());
        } else {
            double q1 = getMedian(values.subList(0, values.size() / 2));
            double q3 = getMedian(values.subList(values.size() / 2, values.size()));
            double iqr = Math.abs(q1 - q3);
            double lowThreshold = q1 - 1.5 * iqr;
            double highThreshold = q3 + 1.5 * iqr;
            Tuple<List<Double>, List<Double>> tuple = new Tuple<>(new ArrayList<>(), new ArrayList<>());

            for (Double value : values) {
                if (value < lowThreshold) {
                    tuple.one.add(value);
                } else if (value > highThreshold) {
                    tuple.two.add(value);
                }
            }

            return tuple;
        }
    }

    public static int getMaximumInt(Collection<Integer> nums) {
        if (nums.isEmpty()) {
            return 0;
        } else {
            int max = Integer.MIN_VALUE;

            for (int val : nums) {
                if (val > max) {
                    max = val;
                }
            }

            return max;
        }
    }

    public static long getMaximumLong(Collection<Long> nums) {
        if (nums.isEmpty()) {
            return 0L;
        } else {
            long max = Long.MIN_VALUE;

            for (long val : nums) {
                if (val > max) {
                    max = val;
                }
            }

            return max;
        }
    }

    public static float getMaximumFloat(Collection<Float> nums) {
        if (nums.isEmpty()) {
            return 0.0F;
        } else {
            float max = Float.MIN_VALUE;

            for (float val : nums) {
                if (val > max) {
                    max = val;
                }
            }

            return max;
        }
    }

    public static double getMinimumDouble(Collection<Double> nums) {
        if (nums.isEmpty()) {
            return 0.0;
        } else {
            double min = Double.MAX_VALUE;

            for (double val : nums) {
                if (val < min) {
                    min = val;
                }
            }

            return min;
        }
    }

    public static int getMinimumInt(Collection<Integer> nums) {
        if (nums.isEmpty()) {
            return 0;
        } else {
            int min = Integer.MAX_VALUE;

            for (int val : nums) {
                if (val < min) {
                    min = val;
                }
            }

            return min;
        }
    }

    public static long getMinimumLong(Collection<Long> nums) {
        if (nums.isEmpty()) {
            return 0L;
        } else {
            long min = Long.MAX_VALUE;

            for (long val : nums) {
                if (val < min) {
                    min = val;
                }
            }

            return min;
        }
    }

    public static float getMinimumFloat(Collection<Float> nums) {
        if (nums.isEmpty()) {
            return 0.0F;
        } else {
            float min = Float.MAX_VALUE;

            for (float val : nums) {
                if (val < min) {
                    min = val;
                }
            }

            return min;
        }
    }

    public static synchronized <T> boolean anyMatch(List<T> objects, Predicate<T> condition) {
        if (condition == null) {
            return false;
        } else {
            for (T object : objects) {
                if (condition.test(object)) {
                    return true;
                }
            }

            return false;
        }
    }

    public static double calculateSum(List<Integer> numbers) {
        return numbers.stream().mapToDouble(Integer::doubleValue).sum();
    }

    public static double calculateProduct(List<Integer> numbers) {
        return numbers.stream().mapToDouble(Integer::doubleValue).reduce(1.0, (a, b) -> a * b);
    }

    public static double calculateSumOfSquares(List<Integer> numbers) {
        return numbers.stream().mapToDouble(num -> Math.pow((double)num.intValue(), 2.0)).sum();
    }

    public static double calculateHarmonicMean(List<Integer> numbers) {
        if (numbers.isEmpty()) {
            return -1.0;
        } else {
            double reciprocalSum = numbers.stream().mapToDouble(num -> 1.0 / (double)num.intValue()).sum();
            return (double)numbers.size() / reciprocalSum;
        }
    }

    public static double calculateGeometricMean(List<Integer> numbers) {
        if (numbers.isEmpty()) {
            return -1.0;
        } else {
            double product = numbers.stream().mapToDouble(Integer::doubleValue).reduce(1.0, (a, b) -> a * b);
            return Math.pow(product, 1.0 / (double)numbers.size());
        }
    }

    public static Map<Integer, Long> countOccurrences(List<Integer> numbers) {
        return numbers.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    public static List<Double> calculateMovingAverageInt(List<Integer> numbers, int windowSize) {
        return (List<Double>)(windowSize > 0 && windowSize <= numbers.size()
                ? IntStream.rangeClosed(0, numbers.size() - windowSize)
                .mapToDouble(i -> numbers.subList(i, i + windowSize).stream().mapToDouble(Integer::doubleValue).average().orElse(0.0))
                .boxed()
                .collect(Collectors.toList())
                : new ArrayList<>());
    }

    public static boolean isPalindrome(List<Integer> numbers) {
        int size = numbers.size();

        for (int i = 0; i < size / 2; i++) {
            if (!numbers.get(i).equals(numbers.get(size - i - 1))) {
                return false;
            }
        }

        return true;
    }

    public static boolean isPalindromeF(List<Float> numbers) {
        int size = numbers.size();

        for (int i = 0; i < size / 2; i++) {
            if (!numbers.get(i).equals(numbers.get(size - i - 1))) {
                return false;
            }
        }

        return true;
    }

    public static boolean isConsecutivePermutation(List<Integer> numbers) {
        int n = numbers.size();
        List<Integer> sortedList = numbers.stream().sorted().collect(Collectors.toList());

        for (int i = 0; i < n; i++) {
            if (sortedList.get(i) != i + sortedList.get(0)) {
                return false;
            }
        }

        return true;
    }

    public static boolean isConsecutivePermutationF(List<Float> numbers) {
        int n = numbers.size();
        List<Float> sortedList = numbers.stream().sorted().collect(Collectors.toList());

        for (int i = 0; i < n; i++) {
            if (sortedList.get(i) != (float)i + sortedList.get(0)) {
                return false;
            }
        }

        return true;
    }

    public static boolean isArithmeticProgression(List<Integer> numbers) {
        if (numbers.size() < 2) {
            return true;
        } else {
            int commonDifference = numbers.get(1) - numbers.get(0);

            for (int i = 1; i < numbers.size(); i++) {
                if (numbers.get(i) - numbers.get(i - 1) != commonDifference) {
                    return false;
                }
            }

            return true;
        }
    }

    public static boolean isArithmeticProgressionF(List<Float> numbers) {
        if (numbers.size() < 2) {
            return true;
        } else {
            float commonDifference = numbers.get(1) - numbers.get(0);

            for (int i = 1; i < numbers.size(); i++) {
                if (numbers.get(i) - numbers.get(i - 1) != commonDifference) {
                    return false;
                }
            }

            return true;
        }
    }

    public static boolean isGeometricProgression(List<Integer> numbers) {
        if (numbers.size() < 2) {
            return true;
        } else {
            int commonRatio = numbers.get(1) / numbers.get(0);

            for (int i = 1; i < numbers.size(); i++) {
                if (numbers.get(i) / numbers.get(i - 1) != commonRatio) {
                    return false;
                }
            }

            return true;
        }
    }

    public static boolean isGeometricProgressionF(List<Float> numbers) {
        if (numbers.size() < 2) {
            return true;
        } else {
            float commonRatio = numbers.get(1) / numbers.get(0);

            for (int i = 1; i < numbers.size(); i++) {
                if (numbers.get(i) / numbers.get(i - 1) != commonRatio) {
                    return false;
                }
            }

            return true;
        }
    }

    public static List<Integer> calculateDifferences(List<Integer> numbers) {
        if (numbers.size() < 2) {
            throw new IllegalArgumentException("List should have at least two elements for differences");
        } else {
            return IntStream.range(1, numbers.size()).mapToObj(i -> numbers.get(i) - numbers.get(i - 1)).collect(Collectors.toList());
        }
    }

    public static int identifyTrend(List<Integer> numbers) {
        List<Integer> differences = calculateDifferences(numbers);
        long increasingCount = differences.stream().filter(diff -> diff > 0).count();
        long decreasingCount = differences.stream().filter(diff -> diff < 0).count();
        if (increasingCount == (long)differences.size()) {
            return 1;
        } else {
            return decreasingCount == (long)differences.size() ? -1 : 0;
        }
    }

    public static <T> boolean allMatch(Collection<T> collection, Predicate<T> condition) {
        if (condition == null) {
            return false;
        } else {
            for (T object : collection) {
                if (!condition.test(object)) {
                    return false;
                }
            }

            return true;
        }
    }

    public static <T> List<T> getFiltered(Collection<T> data, Predicate<T> filter) {
        List<T> list = new LinkedList<>();
        if (filter != null && !data.isEmpty()) {
            for (T object : data) {
                if (filter.test(object)) {
                    list.add(object);
                }
            }

            return list;
        } else {
            return list;
        }
    }

    public static int filteredCount(Collection<? extends Number> data, Predicate<Number> filter) {
        if (filter != null && !data.isEmpty()) {
            int count = 0;

            for (Number num : data) {
                if (filter.test(num)) {
                    count++;
                }
            }

            return count;
        } else {
            return 0;
        }
    }
}

