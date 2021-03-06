package com.flightstats.filesystem;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.util.IOUtils;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.io.ByteStreams;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.collect.Lists.transform;

public class S3FileSystem implements FileSystem {
    private final static Logger logger = LoggerFactory.getLogger(S3FileSystem.class);

    private final AmazonS3 s3;
    private final String bucketName;

    public S3FileSystem(AmazonS3 s3, String bucketName) {
        this.s3 = s3;
        this.bucketName = bucketName;
    }

    private String makeFileName(Path file) {
        return Joiner.on("/").join(file.iterator());
    }

    @Override
    public OutputStream outputStream(Path fileName) {
        return outputStream(fileName, null);
    }

    @Override
    public OutputStream outputStream(Path fileName, String contentType) {
        return new ChunkingS3OutputStream(makeFileName(fileName), contentType);
    }

    @Override
    @SneakyThrows
    public String readContents(Path fileName) {
        return IOUtils.toString(inputStream(fileName));
    }

    @Override
    @SneakyThrows
    public boolean exists(Path fileName) {
        try {
            S3Object object = s3.getObject(bucketName, makeFileName(fileName));
            object.close();
            return true;
        } catch (AmazonS3Exception e) {
            //this is a bit of a kludge-o.  for some reason, we get a 403 when we can't read the file...sometimes.
            if (e.getStatusCode() == 404 || e.getStatusCode() == 403) {
                return false;
            }
            throw e;
        }
    }

    @Override
    @SneakyThrows
    public void saveContent(String content, Path fileName) {
        saveContent(content, fileName, null);
    }

    @Override
    @SneakyThrows
    public void saveContent(String content, Path fileName, String contentType) {
        saveContent(content.getBytes(Charsets.UTF_8), fileName, contentType);
    }

    @SneakyThrows
    @Override
    public void saveContent(byte[] content, Path fileName, String contentType) {
        try (OutputStream outputStream = outputStream(fileName, contentType)) {
            ByteStreams.copy(new ByteArrayInputStream(content), outputStream);
        }
    }

    @Override
    public List<Path> listFiles(Path prefixPath) {
        String prefix = makeFileName(prefixPath);
        List<S3ObjectSummary> summaries = new ArrayList<>();
        ObjectListing objectListing = s3.listObjects(bucketName, prefix);
        summaries.addAll(objectListing.getObjectSummaries());
        while (objectListing.isTruncated()) {
            objectListing = s3.listNextBatchOfObjects(objectListing);
            summaries.addAll(objectListing.getObjectSummaries());
        }
        return transform(summaries, objectSummary -> Paths.get(objectSummary.getKey()));
    }

    @Override
    public void move(Path file, Path destinationDirectory) {
        String sourceKey = makeFileName(file);
        String destinationKey = makeFileName(destinationDirectory.resolve(file.getFileName()));
        s3.copyObject(bucketName, sourceKey, bucketName, destinationKey);
        s3.deleteObject(bucketName, sourceKey);
    }

    @Override
    @SneakyThrows
    public InputStream inputStream(Path fileName) {
        try (S3ObjectInputStream s3ObjectInputStream = s3.getObject(bucketName, makeFileName(fileName)).getObjectContent()) {
            File tempFile = File.createTempFile("s3Temp", null);
            tempFile.deleteOnExit();
            Files.copy(s3ObjectInputStream, Paths.get(tempFile.toURI()), StandardCopyOption.REPLACE_EXISTING);
            return new FileInputStream(tempFile);
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() == 404) {
                throw new UncheckedIOException(new FileNotFoundException("file not found in S3: " + fileName));
            }
            throw e;
        }
    }

    private class ChunkingS3OutputStream extends OutputStream {
        private static final int CHUNK_SIZE = 5 * 1024 * 1024;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(CHUNK_SIZE);
        private final String fileName;
        private InitiateMultipartUploadResult initiateMultipartUploadResult;
        private int partNumber = 0;
        private final List<PartETag> eTags = new ArrayList<>();
        private final String contentType;

        public ChunkingS3OutputStream(String fileName, String contentType) {
            this.fileName = fileName;
            this.contentType = contentType;
        }

        @Override
        public void write(int b) throws IOException {
            bytes.write(b);
            if (bytes.size() >= CHUNK_SIZE) {
                flush();
            }
        }

        @Override
        public void flush() throws IOException {
            doFlush(false);
        }

        private void doFlush(boolean force) {
            if (initiateMultipartUploadResult == null) {
                InitiateMultipartUploadRequest initiateMultipartUploadRequest = new InitiateMultipartUploadRequest(bucketName, fileName);
                if (contentType != null) {
                    ObjectMetadata objectMetadata = new ObjectMetadata();
                    objectMetadata.setContentType(contentType);
                    initiateMultipartUploadRequest.setObjectMetadata(objectMetadata);
                }
                initiateMultipartUploadResult = s3.initiateMultipartUpload(initiateMultipartUploadRequest);
            }
            if (bytes.size() == 0 && partNumber > 0) {
//                logger.info("skipping flushing...zero bytes remaining");
                return;
            }
            if (!force && bytes.size() < CHUNK_SIZE) {
//                logger.info("skipping flushing...not forced and not enough bytes");
                return;
            }
            logger.debug("Flushing to S3 with " + bytes.size() + " bytes");
            UploadPartRequest uploadPartRequest = new UploadPartRequest()
                    .withBucketName(bucketName)
                    .withPartNumber(++partNumber)
                    .withPartSize(bytes.size())
                    .withKey(fileName)
                    .withUploadId(initiateMultipartUploadResult.getUploadId())
                    .withInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            UploadPartResult uploadPartResult = s3.uploadPart(uploadPartRequest);
            eTags.add(uploadPartResult.getPartETag());
            bytes.reset();
        }

        @Override
        public void close() throws IOException {
            doFlush(true);
            if (partNumber == 0 && bytes.size() == 0) {
                //nothing to save, so avoid the S3 error.
                return;
            }
            CompleteMultipartUploadRequest request = new CompleteMultipartUploadRequest(bucketName, fileName, initiateMultipartUploadResult.getUploadId(), eTags);
            s3.completeMultipartUpload(request);
        }
    }
}
