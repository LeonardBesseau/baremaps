package io.gazetteer.tilestore;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import io.gazetteer.postgis.util.DatabaseUtil;
import io.gazetteer.tilestore.file.FileTileStore;
import io.gazetteer.tilestore.model.Tile;
import io.gazetteer.tilestore.model.TileReader;
import io.gazetteer.tilestore.model.TileWriter;
import io.gazetteer.tilestore.model.XYZ;
import io.gazetteer.tilestore.postgis.PostgisConfig;
import io.gazetteer.tilestore.postgis.PostgisLayer;
import io.gazetteer.tilestore.postgis.PostgisTileReader;
import io.gazetteer.tilestore.s3.S3TileStore;
import io.gazetteer.tilestore.util.TileUtil;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.locationtech.jts.geom.Geometry;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(description = "Generate vector tiles from Postgresql")
public class Generator implements Runnable {

  @Parameters(index = "0", paramLabel = "CONFIG_FILE", description = "The YAML configuration config.")
  private Path config;

  @Parameters(index = "1", paramLabel = "POSTGRES_DATABASE", description = "The Postgres database.")
  private String database;

  @Parameters(index = "2", paramLabel = "TILE_DIRECTORY", description = "The tile directory.")
  private File directory;

  @Option(
      names = {"-t", "--threads"},
      description = "The size of the thread pool.")
  private int threads = Runtime.getRuntime().availableProcessors();

  @Override
  public void run() {
    ForkJoinPool executor = new ForkJoinPool(threads);
    try {
      // Read the configuration file
      List<PostgisLayer> layers = PostgisConfig.load(new FileInputStream(config.toFile())).getLayers();
      PoolingDataSource datasource = DatabaseUtil.poolingDataSource(database);
      TileReader tileReader = new PostgisTileReader(datasource, layers);
      AmazonS3 client = AmazonS3ClientBuilder.standard().defaultClient();
      //TileWriter tileWriter = new S3TileStore(client, "gazetteer-tilestore");
      TileWriter tileWriter = new FileTileStore(Paths.get("/tmp/tiles"));

      try (Connection connection = datasource.getConnection()) {
        Geometry geometry = TileUtil.bbox(connection);
        Stream<XYZ> coords = TileUtil.getOverlappingXYZ(geometry, 1, 14);
        executor.submit(() -> coords.forEach(xyz -> {
          try {
            Tile tile = tileReader.read(xyz);
            tileWriter.write(xyz, tile);
          } catch (Exception e) {
            e.printStackTrace();
          }
        }));
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      executor.shutdown();
      try {
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  public static void main(String[] args) {
    CommandLine.run(new Generator(), args);
  }

}
