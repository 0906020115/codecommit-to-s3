package yixiang.devopstool;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.AWSKMSClientBuilder;
import com.amazonaws.services.kms.model.DecryptRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;
import com.amazonaws.util.Base64;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


public class Application {

	public void handleRequest(InputStream request, Context context) throws InvalidRemoteException, TransportException,
			GitAPIException, InvalidKeyException, NoSuchAlgorithmException, IOException {
		LambdaLogger log = context.getLogger();
		JsonParser parser = new JsonParser();
		JsonObject inputObj = null;
		String commitEvent = null;
		String localBasePath = "/tmp/localRepo/";
		String s3Bucket = System.getenv("s3Bucket");
		String s3BasePath = System.getenv("s3BasePath");
		String s3Region = System.getenv("s3Region");
		String httpUrl=System.getenv("httpUrl");
		AmazonS3 s3client = AmazonS3ClientBuilder.standard().withRegion(s3Region)
				.withCredentials(new DefaultAWSCredentialsProviderChain()).build();
		try {
			inputObj = parser.parse(IOUtils.toString(request)).getAsJsonObject();
			commitEvent = inputObj.get("Records").getAsJsonArray().get(0).getAsJsonObject().get("codecommit")
					.getAsJsonObject().get("references").getAsJsonArray().get(0).getAsJsonObject().get("commit")
					.getAsString();
			log.log("commitEvent: " + commitEvent);
		} catch (IOException e) {
			log.log("Error while reading request\n" + e.getMessage());
			throw new RuntimeException(e.getMessage());
		}
		String accessKey = decryptKey(System.getenv("accessKey"));
		String secretKey = decryptKey(System.getenv("secretKey"));
		log.log("accessKey: " + accessKey);
		log.log("secretKey: " + secretKey);
		File localGitRepo = new File(localBasePath);
		Repository repo = null;
		FileUtils.deleteQuietly(localGitRepo);
		List<String> filePathInCommit = null;
		try (Git git = Git.cloneRepository().setURI(httpUrl).setDirectory(localGitRepo)
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(accessKey, secretKey)).call()) {
			repo = git.getRepository();
			Ref head = repo.findRef("HEAD");
			if (!head.getObjectId().getName().equals(commitEvent)) {
				FileUtils.deleteQuietly(localGitRepo);
				return;
			} else {
				ObjectId parentHead = git.getRepository().resolve("HEAD^^{tree}");
				log.log("parent commit: " + parentHead.getName());
				ObjectId childHead = git.getRepository().resolve("HEAD^{tree}");
				log.log("child commit: " + childHead.getName());
				ObjectReader reader = repo.newObjectReader();
				CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
				oldTreeIter.reset(reader, parentHead);
				CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
				newTreeIter.reset(reader, childHead);
				List<DiffEntry> diffs = git.diff().setNewTree(newTreeIter).setOldTree(oldTreeIter).call();
				filePathInCommit = diffs.stream().map(x -> x.getNewPath()).collect(Collectors.toList());
			}
		}
		log.log("finish cloning repo");
		log.log("updated files:");
		filePathInCommit.forEach(x -> log.log(x));
		filePathInCommit.forEach(x -> {
			s3client.putObject(new PutObjectRequest(s3Bucket, s3BasePath + "/" + x, new File(localBasePath + x)));
			log.log("uploaded " + x);
		});

		FileUtils.deleteQuietly(localGitRepo);
		sendEmail(filePathInCommit);
		return;
	}

	private static String decryptKey(String encryptedString) {
		System.out.println("Decrypting key");
		byte[] encryptedKey = Base64.decode(encryptedString);
		AWSKMS client = AWSKMSClientBuilder.defaultClient();
		DecryptRequest request = new DecryptRequest().withCiphertextBlob(ByteBuffer.wrap(encryptedKey));
		ByteBuffer plainTextKey = client.decrypt(request).getPlaintext();
		return new String(plainTextKey.array(), Charset.forName("UTF-8"));
	}

	private static void sendEmail(List<String> filePath) {
		String sesRegion=System.getenv("sesRegion");
		String fromEmail=System.getenv("fromEmail");
		String[] toEmailList = System.getenv("toEmailList").split(",");
		Destination destination = new Destination().withToAddresses(toEmailList);
		Content subject = new Content().withData("Configuration Update");
		StringBuilder sb = new StringBuilder();
		sb.append("The following configuration(s) have been changed: \n");
		filePath.forEach(x -> sb.append(x + "\n"));
		Content textBody = new Content().withData(sb.toString());
		Body body = new Body().withText(textBody);
		Message message = new Message().withSubject(subject).withBody(body);
		SendEmailRequest request = new SendEmailRequest().withSource(fromEmail).withDestination(destination)
				.withMessage(message);
		try {
			AmazonSimpleEmailService client = AmazonSimpleEmailServiceClientBuilder.standard()
					.withCredentials(new DefaultAWSCredentialsProviderChain()).withRegion(fromEmail).build();
			client.sendEmail(request);
			System.out.println("Email sent!");
		} catch (Exception ex) {
			System.out.println("The email was not sent.");
			System.out.println("Error message: " + ex.getMessage());
		}
	}
}
