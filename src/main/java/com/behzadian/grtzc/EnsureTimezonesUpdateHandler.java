package com.behzadian.grtzc;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PutItemOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagement;
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class EnsureTimezonesUpdateHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
	Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private static final AWSSimpleSystemsManagement ssm = AWSSimpleSystemsManagementClientBuilder.defaultClient();
	private AmazonDynamoDB amazonDynamoDB;
	private final String DYNAMODB_TABLE_NAME = "grtzc-timezones";
	private final Regions REGION = Regions.US_EAST_1;

	@Override
	public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
		try {
			return execute();
		} catch (IOException | URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	private APIGatewayV2HTTPResponse execute() throws IOException, URISyntaxException {
		this.amazonDynamoDB = AmazonDynamoDBClientBuilder.standard().withRegion(REGION).build();

		URL tzUri = new URL(getProperty("grtzc_tz_src_url"));
		ReadableByteChannel readableByteChannel = Channels.newChannel(tzUri.openStream());
		String dbZip = "/tmp/db.zip";
		String dbFolder = "/tmp/db/";
		try (FileOutputStream fileOutputStream = new FileOutputStream(dbZip)) {
			fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
			unzip(dbZip, dbFolder);
			File dbPath = new File(dbFolder);
			String[] files = dbPath.list();
			if (files == null)
				return null;
			String countriesFile = null;
			String timeZonesFile = null;
			for (String file : files) {
				if (!file.endsWith(".csv"))
					continue;
				if (file.endsWith("country.csv")) {
					countriesFile = file;
					continue;
				}
				if (file.endsWith("time_zone.csv")) {
					timeZonesFile = file;
					continue;
				}
			}
			if (countriesFile == null)
				return null;
			if (timeZonesFile == null)
				return null;
			HashMap<String, String> countries = importCountries(dbFolder + countriesFile);
			importTimezones(dbFolder + timeZonesFile, countries);
		}
		return null;
	}

	private void importTimezones(String timeZonesFile, HashMap<String, String> countries) throws IOException {
		try (Stream<String> lines = Files.lines(Path.of(timeZonesFile))) {
			DynamoDB dynamoDB = new DynamoDB(amazonDynamoDB);
			Table table = dynamoDB.getTable(DYNAMODB_TABLE_NAME);
			lines.forEach(line -> {

				String[] vals = line.split(",");
				if (vals.length != 6)
					throw new RuntimeException("line has not exactly two parts: " + line);
				String continentCity = vals[0];
				String continent = continentCity.split("/")[0];
				String city = continentCity.split("/")[1];
				String countryCode = vals[1];
				String countryName = countries.get(countryCode);
				String tzCode = vals[2];
				long begin = tryParseLong(vals[3], 0);
				int offset = tryParseInt(vals[4], 0);
				boolean dst = tryParseBoolean(vals[5]);

				Item item = new Item()
						.withPrimaryKey("ID", continentCity + "@" + begin)
						.withString("Continent", continent)
						.withString("CountryCode", countryCode)
						.withString("CountryName", countryName)
						.withString("City", city)
						.withString("TimezoneAbbr", tzCode)
						.withNumber("Beginning", begin)
						.withNumber("Offset", offset)
						.withBoolean("DaylightSavingTime", dst);

				PutItemOutcome outcome = table.putItem(item);
			});
		}
	}

	private boolean tryParseBoolean(String val) {
		if (val.equalsIgnoreCase("1")) return true;
		if (val.equalsIgnoreCase("0")) return false;
		throw new RuntimeException("Unable to convert value [" + val + "] to boolean");
	}

	private int tryParseInt(String text, int defaultValue) {
		try {
			return Integer.parseInt(text);
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private HashMap<String, String> importCountries(String file) throws IOException {
		HashMap<String, String> map = new HashMap<>();
		try (Stream<String> lines = Files.lines(Path.of(file))) {
			lines.forEach(line -> {
				String[] p = line.split(",");
				if (p.length < 2)
					throw new RuntimeException("line has less than two parts: " + line);
				String key = p[0];
				String country = line.substring(key.length() + 1).trim();
				if (map.containsKey(key))
					throw new RuntimeException("country " + key + " is already added to map");
				map.put(key, country);
			});
		}
		return map;
	}

	public void unzip(String zipFilePath, String destDirectory) throws IOException {
		File destDir = new File(destDirectory);
		if (!destDir.exists()) {
			destDir.mkdir();
		}
		ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(Paths.get(zipFilePath)));
		ZipEntry entry = zipIn.getNextEntry();
		// iterates over entries in the zip file
		while (entry != null) {
			String filePath = destDirectory + File.separator + entry.getName();
			if (!entry.isDirectory()) {
				// if the entry is a file, extracts it
				extractFile(zipIn, filePath);
			} else {
				// if the entry is a directory, make the directory
				File dir = new File(filePath);
				dir.mkdirs();
			}
			zipIn.closeEntry();
			entry = zipIn.getNextEntry();
		}
		zipIn.close();
	}

	private void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
		BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
		byte[] bytesIn = new byte[1024];
		int read = 0;
		while ((read = zipIn.read(bytesIn)) != -1) {
			bos.write(bytesIn, 0, read);
		}
		bos.close();
	}

	private static long tryParseLong(String text, long defaultValue) {
		try {
			return Long.parseLong(text);
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private String getProperty(String name) {
		GetParameterRequest request = new GetParameterRequest().withName(name);
		GetParameterResult parameter = ssm.getParameter(request);
		return parameter.getParameter().getValue();
	}
}