/*
 * Copyright (C) 2019  Lightbend
 *
 * This file is part of ModelServing-tutorial
 *
 * ModelServing-tutorial is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.lightbend.modelserving.spark.server

import com.lightbend.model.winerecord.WineRecord
import com.lightbend.modelserving.configuration.ModelServingConfiguration
import com.lightbend.modelserving.model.{ModelToServe, ServingResult}
import com.lightbend.modelserving.spark.{DataWithModel, KafkaSupport, ModelState}
import com.lightbend.modelserving.winemodel.WineFactoryResolver
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection._

/**
  * Implementation of Model serving using Spark Structured server with real time support.
  */

object SparkStructuredStateModelServer {

  import ModelServingConfiguration._

  def main(args: Array[String]): Unit = {

    println(s"Running Spark Model Server. Kafka: $KAFKA_BROKER ")

    // Create context
    val sparkSession = SparkSession.builder
      .appName("SparkModelServer")
      .master("local[3]")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.kryo.registrator", "com.lightbend.modelserving.spark.ModelStateRegistrator")
      .config("spark.sql.streaming.checkpointLocation", CHECKPOINT_DIR)
      .getOrCreate()

    sparkSession.sparkContext.setLogLevel("ERROR")
    import sparkSession.implicits._

    // Set modelToServe
    ModelToServe.setResolver(WineFactoryResolver)

    val ssc = new StreamingContext(sparkSession.sparkContext, Seconds(1))
    ssc.checkpoint("./cpt")

    // Message parsing
    sparkSession.udf.register("deserializeData", (data: Array[Byte]) => DataWithModel.dataWineFromByteArrayStructured(data))

    // Current state of data models
    val currentModels = mutable.Map[String, ModelState]()

    // Create data stream
    val datastream = sparkSession
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", KAFKA_BROKER)
      .option("subscribe", DATA_TOPIC)
      .option(ConsumerConfig.GROUP_ID_CONFIG, DATA_GROUP)
      .option(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .load()
      .selectExpr("""deserializeData(value) AS data""")
      .select("data.fixedAcidity", "data.volatileAcidity", "data.citricAcid", "data.residualSugar",
        "data.chlorides", "data.freeSulfurDioxide", "data.totalSulfurDioxide", "data.density", "data.pH",
        "data.sulphates", "data.alcohol", "data.dataType", "data.ts"
      )
      .as[WineRecord]
      .map(data => {
        data.dataType match {
          case dtype if(dtype != "") =>
            currentModels.get (data.dataType) match {
              case Some (state) =>
                val result = state.model.score (data.asInstanceOf[AnyVal] ).asInstanceOf[Double]
                ServingResult (state.name, data.dataType, System.currentTimeMillis () - data.ts, result)
              case _ => ServingResult ("No model available")
            }
          case _ => ServingResult ("Bad input record")
        }
      }).as[ServingResult]

    var dataQuery = datastream
      .writeStream.outputMode("update").format("console")
      .trigger(Trigger.Continuous("5 second"))
      .start

    // Create models kafka stream
    val kafkaParams = KafkaSupport.getKafkaConsumerConfig(KAFKA_BROKER)
    val modelsStream = KafkaUtils.createDirectStream[Array[Byte], Array[Byte]](ssc,PreferConsistent,
      Subscribe[Array[Byte], Array[Byte]](Set(MODELS_TOPIC),kafkaParams))

    modelsStream.foreachRDD( rdd =>
      if (!rdd.isEmpty()) {
        val models = rdd.map(_.value).collect
          .map(ModelToServe.fromByteArray(_)).filter(_.isSuccess).map(_.get)
        val newModels = models.map(modelToServe => {
          println (s"New model ${modelToServe}")
          // Update state with the new model
          val model = WineFactoryResolver.getFactory(modelToServe.modelType) match {
            case Some (factory) => factory.create(modelToServe)
            case _ => None
          }
          model match {
            case Some (m) => (modelToServe.dataType, ModelState (modelToServe.name, m) )
            case _ => (null, null)
          }
        }).toMap

        // Stop currently running data stream
        println("Stopping data query")
        dataQuery.stop

        // Merge maps
        newModels.foreach{ case (name, value) => {
          if(currentModels.contains(name))
            currentModels(name).model.cleanup()
          currentModels(name) = value
        }}

        // restatrt data stream
        println("Starting data query")
        dataQuery = datastream
          .writeStream.outputMode("update").format("console")
          .trigger(Trigger.Continuous("5 second"))
          .start
      }
    )

    // Execute
    ssc.start()
    ssc.awaitTermination()
  }
}