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

package com.lightbend.modelserving.akka

import akka.Done
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext}
import com.lightbend.model.winerecord.WineRecord
import com.lightbend.modelserving.model.{Model, ModelToServe, ModelToServeStats, ServingResult}

class ModelServerBehaviour(context: ActorContext[ModelServerActor], dataType : String) extends AbstractBehavior[ModelServerActor] {

  println(s"Creating a new Model Server for data type $dataType")

  private var currentModel: Option[Model] = None
  private var newModel: Option[Model] = None
  var currentState: Option[ModelToServeStats] = None
  private var newState: Option[ModelToServeStats] = None

  override def onMessage(msg: ModelServerActor): Behavior[ModelServerActor] = {
    msg match {
      case model : ModelUpdate => // Update Model
        // Update model
        println(s"Updated model: ${model.model}")
        newState = Some(new ModelToServeStats(model.model))
        newModel = ModelToServe.toModel(model.model)
        model.reply ! Done
      case record : ServeData => // Serve datat
        // See if we have update for the model
        newModel.foreach { model =>
          // close current model first
          currentModel.foreach(_.cleanup())
          // Update model
          currentModel = newModel
          currentState = newState
          newModel = None
        }
        // Actually process data
        val result = currentModel match {
          case Some(model) => {
            val start = System.currentTimeMillis()
            // Actually serve
            val result = model.score(record.record.getRecord)
            val duration = System.currentTimeMillis() - start
            // Update state
            currentState = Some(currentState.get.incrementUsage(duration))
            // result
            Some(ServingResult(currentState.get.name, record.record.getType, record.record.getRecord.asInstanceOf[WineRecord].ts, result.asInstanceOf[Double]))
          }
          case _ => None
        }
        record.reply ! result
      case getState : GetState => // State query
        getState.reply ! currentState.getOrElse(ModelToServeStats())
    }
    this
  }
}