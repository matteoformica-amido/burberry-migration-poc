package com.burberry.datachecker;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.http.entity.ContentType;
import org.json.JSONObject;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.BatchGetItemOutcome;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.TableKeysAndAttributes;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.burberry.datachecker.model.CompareRequest;
import com.burberry.datachecker.model.CustomerRecord;
import com.burberry.datachecker.model.ErrorDetail;
import com.burberry.datachecker.model.ResponseClass;

public class DataCompareHandlerSequentialBatched implements RequestHandler<CompareRequest, ResponseClass> {

	static AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard().build();
	AmazonS3 s3client = AmazonS3ClientBuilder.standard().build();
	static DynamoDB dynamoDB = new DynamoDB(client);

	static int recordNotFound = 0;
	static int recordFound = 0;
	static int recordFoundNotMatching = 0;
	static int recordFoundMatching = 0;
	String bucketName;
	static List<ErrorDetail> errorDetails;
	static ResponseClass rsp;
	static final int BATCH_SIZE = 100;

	StringBuilder sb = new StringBuilder("");
	Formatter fmt = new Formatter(sb);

	@Override
	public ResponseClass handleRequest(CompareRequest input, Context context) {
		
		System.out.format("Input received: %s \n", input);
		
		bucketName = input.getBucket();
		
		recordNotFound = 0;
		recordFound = 0;
		recordFoundMatching = 0;
		recordFoundNotMatching = 0;
		errorDetails = new ArrayList<ErrorDetail>();
		
		if (!s3client.doesBucketExistV2(bucketName)) {
			System.out.format("Bucket name is not available: %s", bucketName);
			System.exit(1);
		}
		
		scanATGtable(input);

		fmt.format("Record found: %s \n", recordFound);
		fmt.format("Record not found: %s \n", recordNotFound);

		System.out.format("Record found: %s \n", recordFound);
		System.out.format("Record not found: %s \n", recordNotFound);
		System.out.format("Record found matching: %s \n", recordFoundMatching);
		System.out.format("Record found not matching : %s \n", recordFoundNotMatching);

		rsp = new ResponseClass(recordFound, recordFoundMatching, recordNotFound, recordFoundNotMatching,
				errorDetails);
		JSONObject s3Report = new JSONObject(rsp);
		dumpReportToS3(input, s3Report.toString(2));
		// remove list of error messages from Lambda output
		rsp.setMessages(null);
		System.out.format("PROCESSED RECORDS : %s \n", rsp.toString());
		return rsp;
	}

	private void scanATGtable(CompareRequest input) {

		Map<String, AttributeValue> lastKey = null;

		ScanRequest scanRequest = new ScanRequest()
				.withTableName(input.getSourceTable())
				.withExclusiveStartKey(lastKey)
				.withProjectionExpression("customerId, attributesHash, fileName");
		
		do {
			List<CustomerRecord> scannedCustomers = new ArrayList<>();
			ScanResult result = client.scan(scanRequest);
			int scanIterationCounter = 0;
			List<CustomerRecord> leftovers = new ArrayList<>();
			for (Map<String, AttributeValue> item : result.getItems()) {
				
				if((scanIterationCounter+1) % (BATCH_SIZE+1) != 0) {
					scannedCustomers.add(new CustomerRecord(
						item.get("customerId").getS(),
						item.get("attributesHash").getS(), 
						item.get("fileName").getS()));
				}else {
					queryTargetBatch(input, scannedCustomers);
					scannedCustomers = new ArrayList<CustomerRecord>();
					leftovers.add(new CustomerRecord(
						item.get("customerId").getS(),
						item.get("attributesHash").getS(), 
						item.get("fileName").getS()));
				} 
				scanIterationCounter++;
			}
			
			queryTargetBatch(input, scannedCustomers);			
			queryTargetBatch(input, leftovers);
			lastKey = result.getLastEvaluatedKey();			
			scanRequest.setExclusiveStartKey(lastKey);
		} while (lastKey != null);
	}
	
	private static void queryTargetBatch(CompareRequest input, List<CustomerRecord> scannedCustomers) {

		List<String> ids = scannedCustomers.stream().map(x -> x.getCustomerId()).collect(Collectors.toList());
		
		TableKeysAndAttributes targetTableKeysAndAttributes = new TableKeysAndAttributes(input.getTargetTable());
		targetTableKeysAndAttributes.addHashOnlyPrimaryKeys("customerId", ids.toArray())
				.withProjectionExpression("customerId, attributesHash, fileName");

		BatchGetItemOutcome outcome = dynamoDB.batchGetItem(targetTableKeysAndAttributes);
		List<Item> items = outcome.getTableItems().get(input.getTargetTable());
				
		Map<String, CustomerRecord> queryResultMap = new HashMap<String, CustomerRecord>();
		for (Item i : items) {
			queryResultMap.put(i.getString("customerId"),
					new CustomerRecord(i.getString("customerId"), i.getString("attributesHash"), i.getString("fileName")));
		}

		for (CustomerRecord mainRecord : scannedCustomers) {
			CustomerRecord targetItem = queryResultMap.get(mainRecord.getCustomerId());

			if (targetItem != null) {
				if (input.getWriteReport()) {
					if (!targetItem.getHash().equals(mainRecord.getHash())) {
						recordFoundNotMatching++;
						ErrorDetail err = new ErrorDetail("The attributes for the customer in the 2 tables are different!",
								targetItem.getCustomerId(), mainRecord.getFilename(), targetItem.getFilename());
						errorDetails.add(err);
					} else {
						recordFoundMatching++;
					}
				}
				recordFound++;
			} else {
				if (input.getWriteReport()) {
					ErrorDetail err = new ErrorDetail("The customer has not been found in CDC table",
							mainRecord.getCustomerId(), mainRecord.getFilename(), null);
					errorDetails.add(err);
				}
				recordNotFound++;
			}
		}
	}

	private void dumpReportToS3(CompareRequest input, String report) {
		if (input.getWriteReport()) {
			InputStream targetStream = new ByteArrayInputStream(report.toString().getBytes());
			try {
				ObjectMetadata meta = new ObjectMetadata();
				meta.setContentLength(report.toString().length());
				meta.setContentType(ContentType.TEXT_PLAIN.toString());
				s3client.putObject(bucketName,
						new SimpleDateFormat("'COMPARE_RESULTS_'yyyyMMddHHmm'.txt'").format(new Date()), targetStream,
						meta);
			} catch (AmazonServiceException e) {
				System.err.println(e.getErrorMessage());
				System.exit(1);
			}
		} else {
			System.out.format("WRITE_REPORT set to false: skipping report generation");
		}
	}

}
