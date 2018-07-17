package com.prisma.api.connector.jdbc.database

import java.util.Date

import com.prisma.api.connector.{Filter, PrismaArgs}
import com.prisma.gc_values._
import com.prisma.shared.models.TypeIdentifier.IdTypeIdentifier
import com.prisma.shared.models.{Model, TypeIdentifier}
import org.joda.time.{DateTime, DateTimeZone}
import slick.dbio.DBIOAction

import scala.concurrent.ExecutionContext

trait NodeActions extends BuilderBase with FilterConditionBuilder with ScalarListActions with RelayIdActions {
  import slickDatabase.profile.api._

  def createNode(model: Model, args: PrismaArgs): DBIO[IdGCValue] = {
    val idIsAutoGenerated = model.idField_!.isAutoGenerated
    val argsAsRoot = if (idIsAutoGenerated) {
      args.raw.asRoot
    } else {
      args.raw.asRoot.add(model.idField_!.name, generateId(model))
    }

    val fields = model.fields.filter(field => argsAsRoot.hasArgFor(field.name))
    val query = sql
      .insertInto(modelTable(model))
      .columns(fields.map(field => modelColumn(model, field)): _*)
      .values(placeHolders(fields))

    insertReturningGeneratedKeysToDBIO(query)(
      setParams = { pp =>
        val currentTimestamp = currentSqlTimestampUTC
        fields.foreach { field =>
          argsAsRoot.map(field.name) match {
            case NullGCValue if field.name == createdAtField || field.name == updatedAtField => pp.setTimestamp(currentTimestamp)
            case gcValue                                                                     => pp.setGcValue(gcValue)
          }
        }
      },
      readResult = { rs =>
        if (idIsAutoGenerated) {
          rs.next()
          rs.getId(model)
        } else {
          argsAsRoot.idField
        }
      }
    )
  }

  private def generateId(model: Model) = {
    model.idField_!.typeIdentifier.asInstanceOf[IdTypeIdentifier] match {
      case TypeIdentifier.UUID => UuidGCValue.random()
      case TypeIdentifier.Cuid => CuidGCValue.random()
      case TypeIdentifier.Int  => sys.error("can't generate int ids")
    }
  }

  def updateNodeById(model: Model, id: IdGCValue, updateArgs: PrismaArgs): DBIO[_] = {
    if (updateArgs.raw.asRoot.map.isEmpty) {
      DBIOAction.successful(id)
    } else {
      val actualArgs = addUpdatedAt(model, updateArgs.raw.asRoot)
      val columns    = actualArgs.map.map { case (k, _) => model.getFieldByName_!(k).dbName }.toList
      val values     = actualArgs.map.map { case (_, v) => v }

      val query = sql
        .update(modelTable(model))
        .setColumnsWithPlaceHolders(columns)
        .where(idField(model).equal(placeHolder))

      updateToDBIO(query)(
        setParams = { pp =>
          values.foreach(pp.setGcValue)
          pp.setGcValue(id)
        }
      )
    }
  }

  private def addUpdatedAt(model: Model, updateValues: RootGCValue): RootGCValue = {
    model.updatedAtField match {
      case Some(updatedAtField) =>
        val today              = new Date()
        val exactlyNow         = new DateTime(today).withZone(DateTimeZone.UTC)
        val currentDateGCValue = DateTimeGCValue(exactlyNow)
        updateValues.add(updatedAtField.name, currentDateGCValue)
      case None =>
        updateValues
    }
  }

  def updateNodes(model: Model, args: PrismaArgs, whereFilter: Option[Filter]): DBIO[_] = {
    val argsMap = args.raw.asRoot.map
    if (argsMap.nonEmpty) {
      val aliasedTable = modelTable(model).as(topLevelAlias)
      val condition    = buildConditionForFilter(whereFilter)

      val columns = argsMap.map { case (k, _) => model.getFieldByName_!(k).dbName }.toList
      val query = sql
        .update(aliasedTable)
        .setColumnsWithPlaceHolders(columns)
        .where(condition)

      updateToDBIO(query)(
        setParams = pp => {
          argsMap.foreach { case (_, v) => pp.setGcValue(v) }
          whereFilter.foreach(filter => SetParams.setFilter(pp, filter))
        }
      )
    } else {
      dbioUnit
    }
  }

  def deleteNodeById(model: Model, id: IdGCValue)(implicit ec: ExecutionContext) = deleteNodes(model, Vector(id))

  //Todo check how much of a performance gain it would be to chain these using andThen instead of the for comprehension
  def deleteNodes(model: Model, ids: Vector[IdGCValue])(implicit ec: ExecutionContext): DBIO[Unit] = {
    for {
      _ <- deleteScalarListValuesByNodeIds(model, ids)
      _ <- deleteRelayIds(ids)
      _ <- deleteNodesByIds(model, ids)
    } yield ()
  }

  private def deleteNodesByIds(model: Model, ids: Vector[IdGCValue]): DBIO[Unit] = {
    val query = sql
      .deleteFrom(modelTable(model))
      .where(idField(model).in(placeHolders(ids)))

    deleteToDBIO(query)(
      setParams = pp => ids.foreach(pp.setGcValue)
    )
  }

  private val dbioUnit = DBIO.successful(())
}