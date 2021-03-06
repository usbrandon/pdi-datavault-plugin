/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Copyright (c) 2014 Martin Ouellet
 *
 */
package plugin.dvloader.trans.steps.common;

import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.Const;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.exception.KettleDatabaseException;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaDate;
import org.pentaho.di.core.row.value.ValueMetaInteger;
import org.pentaho.di.core.row.value.ValueMetaString;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.step.BaseStepData;
import org.pentaho.di.trans.step.BaseStepMeta;
import org.pentaho.di.trans.step.StepDataInterface;

/**
 * 
 * Data object usable for both Hub and Link.  It encapsulates
 * data/database logic that are shared between these two.
 * 
 * @author mouellet
 * 
 */
public class LoadHubLinkData extends BaseStepData implements StepDataInterface {
	private static Class<?> PKG = BaseLoadMeta.class;

	public Database db;
	// to add an extra pkey for Hub or Link
	public RowMetaInterface outputRowMeta;

	// hold the real schema name (after any var substitution)
	private String realSchemaName;
	// hold the name schema.table (after any var substitution)
	private String qualifiedTable;

	// position index of composite key fields in row stream
	private int[] keysRowIdx;

	// position index of none-key fields in row stream (defined in UI)
	private int[] nonekeysRowIdx;
	
	//position index of fields stored in BINARY in row stream (= null if none)
	private int[] fieldsInBinary = null;

	private RowMetaInterface lookupRowMeta;
	private RowMetaInterface insertRowMeta;

	// Buffer storing original input rows appended with new surrKey
	private List<Object[]> bufferRows;

	// hold the lookup record (key(s) --> PKey)
	private Map<CompositeKeys, Long> lookupMapping;

	public boolean finishedAllRows = false;

	private LogChannelInterface log;

	private PreparedStatement prepStmtLookup;
	private PreparedStatement prepStmtInsert;

	// Use to get/refresh the loadDTS
	private Date nowDate;

	public LoadHubLinkData(LogChannelInterface log) {
		super();
		this.log = log;
		db = null;
	}

	/*
	 * Must be called prior to Prepared Stmt initialization and row processing
	 */
	public void initializeRowProcessing(BaseLoadMeta meta) throws KettleStepException {
		if (bufferRows == null) {
			bufferRows = new ArrayList<Object[]>(meta.getBufferSize() + 10);
		}
		if (lookupMapping == null) {
			int capacity = (int) ((meta.getBufferSize()) / 0.75 + 1);
			lookupMapping = new HashMap<CompositeKeys, Long>(capacity);
		}
		initRowIdx(meta);

		// initialize all naming needing variable substitution (${var})
		realSchemaName = meta.getDatabaseMeta().environmentSubstitute(meta.getSchemaName());
		String realtable = meta.getDatabaseMeta().environmentSubstitute(meta.getTargetTable());
		qualifiedTable = meta.getDatabaseMeta().getQuotedSchemaTableCombination(realSchemaName, realtable);
		if (!Const.isEmpty(meta.getAuditRecSourceCol())){
			meta.setAuditRecSourceValue(meta.getDatabaseMeta().environmentSubstitute(meta.getAuditRecSourceValue()) );
		}

	}

	public Long getKeyfromLookupMap(Object[] originalRow) {
		CompositeKeys n = new CompositeKeys(originalRow, keysRowIdx);
		return lookupMapping.get(n);
	}

	public boolean putKeyInMap(Object[] originalRow, Long valKey) {
		CompositeKeys n = new CompositeKeys(originalRow, keysRowIdx);
		if (lookupMapping.containsKey(n)) {
			return false;
		} else {
			lookupMapping.put(n, valKey);
			return true;
		}
	}

	/**
	 * 
	 * Populate bufferLookupMapping from lookup Query result. ValueMeta in
	 * LookupMeta MUST follow same order as parameters found in rows using their
	 * position index: keysPosInRow
	 * 
	 * @param rows
	 * @param nbParamsClause
	 * @return number of row with successful lookup
	 * @throws KettleDatabaseException
	 */
	public int populateMap(List<Object[]> rows, int nbParamsClause) throws KettleDatabaseException {
		// clean-up previous map
		lookupMapping.clear();

		for (int i = 0; i < nbParamsClause; i++) {
			Object[] p;
			// in case, we have less than buffer size
			try {
				p = rows.get(i);
			} catch (IndexOutOfBoundsException e) {
				p = null;
			}
			for (int j = 0; j < keysRowIdx.length; j++) {
				int pIdx = (i * keysRowIdx.length) + (j + 1);
				// Rely on key params of lookupRowMeta positioned
				// after TechKeyCol (hence j+1) with same order as in UI
				db.setValue(prepStmtLookup, lookupRowMeta.getValueMeta(j + 1), 
						(p == null) ? null : p[keysRowIdx[j]],pIdx);
			}
		}

		ResultSet rs;
		try {
			rs = prepStmtLookup.executeQuery();
			// release prepared Stmt params
			prepStmtLookup.clearParameters();
		} catch (SQLException e) {
			throw new KettleDatabaseException("Unable to execute Lookup query", e);
		}

		for (Object[] r : getLookupRows(rs, keysRowIdx.length + 1, nbParamsClause)) {
			CompositeKeys v = new CompositeKeys(r, 1, keysRowIdx.length);
			lookupMapping.put(v, (Long) r[0]);
		}
		return lookupMapping.size();
	}

	// Not using Database.getRows() as it calls getOneRow(string sql)
	// which changes metaRow instance variable in Database!
	// This interferes with Database.getNextValue used with TABLE-MAX
	private List<Object[]> getLookupRows(ResultSet rs, int nbcols, int maxrows) throws KettleDatabaseException {

		List<Object[]> result = new ArrayList<Object[]>(maxrows);
		boolean stop = false;
		int n = 0;
		try {
			while (!stop && n < maxrows) {
				Object[] row = new Object[nbcols];
				if (rs.next()) {
					for (int i = 0; i < nbcols; i++) {
						ValueMetaInterface val = lookupRowMeta.getValueMeta(i);
						row[i] = db.getDatabaseMeta().getValueFromResultSet(rs, val, i);
					}
					result.add(row);
					n++;
				} else {
					stop = true;
				}
			}
			db.closeQuery(rs);
			return result;
		} catch (Exception e) {
			throw new KettleDatabaseException("Unable to get list of rows from ResultSet : ", e);
		}
	}

	/*
	 * 
	 */
	public void initPrepStmtLookup(BaseLoadMeta meta, int bufferSize) throws KettleDatabaseException {

		lookupRowMeta = new RowMeta();
		lookupRowMeta.addValueMeta(new ValueMetaInteger(meta.getTechKeyCol()));

		/*
		 * SELECT <PK>, <compKey1>, <compKey2> .. FROM <table> WHERE ( <key1> =
		 * ? AND <key2> = ? .. ) OR ( <key1> = ? AND <key2> = ? .. ) ... m-times
		 * (m=bufferSize)
		 */

		StringBuffer sql = new StringBuffer(meta.getBufferSize() * 100);
		sql.append(" SELECT ").append(db.getDatabaseMeta().quoteField(meta.getTechKeyCol()));

		StringBuffer nkcols = new StringBuffer(100);
		StringBuffer endClause = new StringBuffer(meta.getBufferSize() * 100);
		endClause.append(" WHERE ").append(Const.CR);

		for (int j = 0; j < bufferSize; j++) {
			endClause.append(" ( ");
			int keyCounter = 0;
			for (int i = 0; i < meta.getCols().length; i++) {
				String colType = meta.getTypes()[i];
				if (colType.equals(meta.getIdKeyTypeString())) {
					if (keyCounter == 0) {
						endClause.append(db.getDatabaseMeta().quoteField(meta.getCols()[i])).append("=?");
					} else {
						endClause.append(" AND ").append(db.getDatabaseMeta().quoteField(meta.getCols()[i]))
								.append("=?");
					}
					// add Meta of key(s) col
					if (j == 0) {
						nkcols.append(", ").append(db.getDatabaseMeta().quoteField(meta.getCols()[i]));
						int tmpMetatype = outputRowMeta.getValueMeta(keysRowIdx[keyCounter]).getType();
						lookupRowMeta.addValueMeta(new ValueMeta(meta.getCols()[i], tmpMetatype));
					}
					keyCounter++;
				}
			}
			endClause.append(" ) ");
			if (j < bufferSize - 1) {
				endClause.append(" OR ");
			}
		}
		sql.append(nkcols);
		sql.append(" FROM ").append(qualifiedTable).append(Const.CR);
		sql.append(endClause);

		try {
			log.logDebug("Prepared statement for Lookup:" + Const.CR + sql);
			prepStmtLookup = db.getConnection().prepareStatement(db.getDatabaseMeta().stripCR(sql));
			if (db.getDatabaseMeta().supportsSetMaxRows()) {
				// lookup cannot return more than BufferSize
				prepStmtLookup.setMaxRows(bufferSize);
			}
		} catch (SQLException ex) {
			throw new KettleDatabaseException(ex);
		}
	}

	public void initPrepStmtInsert(BaseLoadMeta meta) throws KettleDatabaseException {

		/*
		 * This applied for both Hub and Link: Column ordering rule: 
		 * 1- cols composing natural/surr-key (same as for look-up) 
		 * 2- other cols not part of composite keys (optional) 
		 * 3- technical audit columns 
		 * 4- technical Primary key (not applicable for AUTO-INCREMENT) 
		 * 
		 * INSERT INTO table(key1,..,nonekey1,..,sysAudits,..,PKey)
		 * VALUES(?, ?, ? ..)
		 * 
		 * n.b. VALUES when used with Sequence: VALUES( .., seq.nextVal())
		 * This sequence syntax is DB specific
		 * 
		 */
		
		insertRowMeta = new RowMeta();
		String sqlIns = "INSERT INTO " + qualifiedTable + "( ";
		String sqlValues = Const.CR + " VALUES (";

		// ***********************************************
		// 1- Handle composite keys
		// ***********************************************
		int keyCounter = 0;
		for (int i = 0; i < meta.getCols().length; i++) {
			if (meta.getTypes()[i].equals(meta.getIdKeyTypeString())) {
				if (keyCounter == 0) {
					sqlIns += db.getDatabaseMeta().quoteField(meta.getCols()[i]);
					sqlValues += "?";
				} else {
					sqlIns += ", " + db.getDatabaseMeta().quoteField(meta.getCols()[i]);
					sqlValues += ", ?";
				}
				int tmpMetatype = outputRowMeta.getValueMeta(keysRowIdx[keyCounter]).getType();
				insertRowMeta.addValueMeta(new ValueMeta(meta.getCols()[i], tmpMetatype));
				keyCounter++;
			}
		}

		// ***********************************************
		// 2- Handle other optional columns
		// ***********************************************
		int nonekeyCounter = 0;
		for (int i = 0; i < meta.getCols().length; i++) {
			if (meta.getTypes()[i].equals(meta.getOtherTypeString())) {
				sqlIns += ", " + db.getDatabaseMeta().quoteField(meta.getCols()[i]);
				sqlValues += ", ?";

				int tmpMetatype = outputRowMeta.getValueMeta(nonekeysRowIdx[nonekeyCounter]).getType();
				insertRowMeta.addValueMeta(new ValueMeta(meta.getCols()[i], tmpMetatype));
				nonekeyCounter++;
			}
		}

		// ***********************************************
		// 3- Handle audit columns
		// ***********************************************
		if (!Const.isEmpty(meta.getAuditDtsCol())) {
			sqlIns += ", " + db.getDatabaseMeta().quoteField(meta.getAuditDtsCol());
			sqlValues += ", ?";
			insertRowMeta.addValueMeta(new ValueMetaDate(meta.getAuditDtsCol()));
		}
		if (!Const.isEmpty(meta.getAuditRecSourceCol())) {
			sqlIns += ", " + db.getDatabaseMeta().quoteField(meta.getAuditRecSourceCol());
			sqlValues += ", ?";
			insertRowMeta.addValueMeta(new ValueMetaString(meta.getAuditRecSourceCol()));
		}

		// ***********************************************
		// 4- Handle technical key (PK)
		// ***********************************************
		if (meta.getKeyGeneration().equals(BaseLoadMeta.CREATION_METHOD_SEQUENCE)) {
			sqlIns += ", " + db.getDatabaseMeta().quoteField(meta.getTechKeyCol());
			// Hack to remove Select for postgre, and From &dual for Oracle syntax
			//WILL NOT WORK FOR OTHER MORE EXOTIC "getSeqNextvalSQL" (TODO: should be fixed at PDI level)
			String nextStr = db.getDatabaseMeta().getSeqNextvalSQL(meta.getSequenceName()).
							replace("SELECT","").replace("FROM", "").replace("dual","");
			sqlValues += ", " + nextStr;
		} else if (meta.getKeyGeneration().equals(BaseLoadMeta.CREATION_METHOD_TABLEMAX)) {
			sqlIns += ", " + db.getDatabaseMeta().quoteField(meta.getTechKeyCol());
			sqlValues += ", ?";
			insertRowMeta.addValueMeta(new ValueMetaInteger(meta.getTechKeyCol()));
		} else if (meta.getKeyGeneration().equals(BaseLoadMeta.CREATION_METHOD_AUTOINC)) {
			// No need to refer to Column except for placeholder special
			// requirement (ex. Informix)
			// TODO: test this on Informix...
			if (db.getDatabaseMeta().needsPlaceHolder()) {
				sqlIns += ", 0";
			}
		}
		sqlIns += " )";
		sqlValues += ") ";

		String sqlInsert = sqlIns + sqlValues;
		try {
			log.logDebug("Prepared statement for insert :" + Const.CR + sqlInsert);
			prepStmtInsert = db.getConnection().prepareStatement(db.getDatabaseMeta().stripCR(sqlInsert));
		} catch (SQLException ex) {
			throw new KettleDatabaseException(ex);
		}
	}


	public void initRowIdx(BaseLoadMeta meta) throws KettleStepException {
		int nbKey = 0;
		int nbNoneKey = 0;
		int nbBinary = 0;
		for (int i = 0; i < meta.getTypes().length; i++) {
			if (meta.getTypes()[i].equals(meta.getIdKeyTypeString())) {
				nbKey++;
			} else if (meta.getTypes()[i].equals(meta.getOtherTypeString())) {
				nbNoneKey++;
			}
			if (outputRowMeta.indexOfValue(meta.getFields()[i]) < 0) {
				throw new KettleStepException(BaseMessages.getString(PKG, "Load.Exception.FieldNotFound",
						meta.getFields()[i]));
			}
			
			ValueMetaInterface vl = outputRowMeta.getValueMeta
										(outputRowMeta.indexOfValue(meta.getFields()[i]));
			if (vl.isStorageBinaryString()){
				nbBinary++;
			}
		}

		keysRowIdx = new int[nbKey];
		nonekeysRowIdx = new int[nbNoneKey];
		if (nbBinary > 0){
			fieldsInBinary = new int[nbBinary];	
		}
		
		nbKey = 0;
		nbNoneKey = 0;
		nbBinary = 0;
		for (int i = 0; i < meta.getTypes().length; i++) {
			if (meta.getTypes()[i].equals(meta.getIdKeyTypeString())) {
				keysRowIdx[nbKey] = outputRowMeta.indexOfValue(meta.getFields()[i]);
				if (keysRowIdx[nbKey] < 0) {
					throw new KettleStepException(BaseMessages.getString(PKG, "Load.Exception.FieldNotFound",
							meta.getFields()[i]));
				}
				nbKey++;
			} else if (meta.getTypes()[i].equals(meta.getOtherTypeString())) {
				nonekeysRowIdx[nbNoneKey] = outputRowMeta.indexOfValue(meta.getFields()[i]);
				if (nonekeysRowIdx[nbNoneKey] < 0) {
					throw new KettleStepException(BaseMessages.getString(PKG, "Load.Exception.FieldNotFound",
							meta.getFields()[i]));
				}
				nbNoneKey++;
			}
			
			ValueMetaInterface vl = outputRowMeta.getValueMeta
										(outputRowMeta.indexOfValue(meta.getFields()[i]));
			if (vl.isStorageBinaryString()){
				vl.setStorageType(ValueMetaInterface.STORAGE_TYPE_NORMAL);
				fieldsInBinary[nbBinary] = outputRowMeta.indexOfValue(meta.getFields()[i]);
				nbBinary++;
			}
		}
	}

	public boolean addToBufferRows(Object[] r, int bufferSize) {
		if (bufferRows.size() < bufferSize) {
			bufferRows.add(r);
			return (bufferRows.size() < bufferSize);
		} else {
			return false;
		}
	}

	public void addBatchInsert(BaseLoadMeta meta, Object[] oriRow, Long newKey) throws KettleDatabaseException {

		// ***********************************************
		// 1- Handle composite keys & other optional columns
		// ***********************************************
		int pIdx = 0;
		int nonekeyCounter = 0;
		int keyCounter = 0;
		for (int i = 0; i < meta.getCols().length; i++) {
			pIdx = insertRowMeta.indexOfValue(meta.getCols()[i]);
			if (meta.getTypes()[i].equals(meta.getIdKeyTypeString())) {
				db.setValue(prepStmtInsert, insertRowMeta.getValueMeta(pIdx), oriRow[keysRowIdx[keyCounter]], pIdx + 1);
				keyCounter++;
			} else if (meta.getTypes()[i].equals(meta.getOtherTypeString())) {
				db.setValue(prepStmtInsert, insertRowMeta.getValueMeta(pIdx), oriRow[nonekeysRowIdx[nonekeyCounter]],
						pIdx + 1);
				nonekeyCounter++;
			}
		}

		// ***********************************************
		// 2- Handle audit columns
		// ***********************************************
		if (!Const.isEmpty(meta.getAuditDtsCol())) {
			pIdx = insertRowMeta.indexOfValue(meta.getAuditDtsCol());
			db.setValue(prepStmtInsert, insertRowMeta.getValueMeta(pIdx), getNowDate(false), pIdx + 1);
			// log.logBasic("audit :" + getNowDate(true));

		}
		if (!Const.isEmpty(meta.getAuditRecSourceCol())) {
			pIdx = insertRowMeta.indexOfValue(meta.getAuditRecSourceCol());
			db.setValue(prepStmtInsert, insertRowMeta.getValueMeta(pIdx), meta.getAuditRecSourceValue(), pIdx + 1);
			// log.logBasic("audit src:" + meta.getAuditRecSourceValue());
		}

		// ***********************************************
		// 3- Handle technical key (PK)
		// ***********************************************
		if (meta.isMethodTableMax()) {
			pIdx = insertRowMeta.indexOfValue(meta.getTechKeyCol());
			db.setValue(prepStmtInsert, insertRowMeta.getValueMeta(pIdx), newKey, pIdx + 1);
			// log.logBasic("tech key:" + newKey + "  at pos=" + pIdx);
		}

		try {
			prepStmtInsert.addBatch();
			if (log.isRowLevel()) {
				log.logRowlevel("Adding batch values: " + Arrays.deepToString(oriRow));
			}
		} catch (SQLException ex) {
			throw new KettleDatabaseException("Error adding batch for rowMeta: " + insertRowMeta.toStringMeta(), ex);
		}
	}

	// Issues with multi-threading ("# of copies to start.. > 1):  
	// - PG fails on duplicates since batch is aborted at first insertion 
	// - MySQL fails with dead-lock while trying to get lock by diff threads
	public void executeBatchInsert(BaseStepMeta meta, int insertCtnExpected) throws KettleDatabaseException {
		int[] nbIns = null;
		try {
			nbIns = prepStmtInsert.executeBatch();
			prepStmtInsert.clearBatch();
			//commit ASAP for letting others see new keys 
			db.commit();
		} catch (BatchUpdateException ex) {
			nbIns = ex.getUpdateCounts();
			SQLException nextException = ex;
            do {
              log.logError("Seeding batch nested Exception: " + nextException.getMessage());
            } while ( ( nextException = nextException.getNextException() ) != null );
			
			if (insertCtnExpected == nbIns.length) {
				log.logError("BatchUpdateException raised but JDBC driver continued processing rows");
				db.commit();
				// Continue processing, possible causes:
				// Hub: business key(s) already loaded (to be confirmed by later checks)
				// Link: either -violation of FKs unique constraint (same business key issue as Hub) 
				//              -or FK referential integrity (then process will fail during check after...)
			} else {
				throw new KettleDatabaseException("Error during batch, only " + nbIns.length 
						+ " rows processed out of " +insertCtnExpected, ex);
			}
		} catch (SQLException ex) {
			throw new KettleDatabaseException(ex);
		}
	}

	/*
	 * Get the loading DTS fix by load or refresh for each record
	 */
	public Date getNowDate(boolean refresh) {
		if (nowDate == null || refresh) {
			nowDate = new Date(System.currentTimeMillis());
		}
		return nowDate;
	}

	public String getRealSchemaName() {
		return realSchemaName;
	}

	public String getQualifiedTable() {
		return qualifiedTable;
	}

	public List<Object[]> getBufferRows() {
		return bufferRows;
	}

	public int[] getKeysRowIdx() {
		return keysRowIdx;
	}

	public int[] getFieldsInBinary() {
		return fieldsInBinary;
	}

	public PreparedStatement getPrepStmtLookup() {
		return prepStmtLookup;
	}

	public PreparedStatement getPrepStmtInsert() {
		return prepStmtInsert;
	}

	public RowMetaInterface getLookupRowMeta() {
		return lookupRowMeta;
	}

	public Map<CompositeKeys, Long> getLookupMapping() {
		return lookupMapping;
	}

	public RowMetaInterface getInsertRowMeta() {
		return insertRowMeta;
	}

}
