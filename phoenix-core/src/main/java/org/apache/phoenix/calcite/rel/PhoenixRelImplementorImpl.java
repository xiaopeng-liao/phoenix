package org.apache.phoenix.calcite.rel;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

import org.apache.hadoop.hbase.HConstants;
import org.apache.phoenix.calcite.CalciteUtils;
import org.apache.phoenix.calcite.PhoenixTable;
import org.apache.phoenix.calcite.rel.PhoenixRel.ImplementorContext;
import org.apache.phoenix.compile.ColumnProjector;
import org.apache.phoenix.compile.ExpressionProjector;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.RowProjector;
import org.apache.phoenix.compile.TupleProjectionCompiler;
import org.apache.phoenix.coprocessor.MetaDataProtocol;
import org.apache.phoenix.execute.RuntimeContext;
import org.apache.phoenix.execute.TupleProjector;
import org.apache.phoenix.expression.ColumnExpression;
import org.apache.phoenix.expression.CorrelateVariableFieldAccessExpression;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.parse.ParseNodeFactory;
import org.apache.phoenix.schema.ColumnRef;
import org.apache.phoenix.schema.KeyValueSchema;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PColumnImpl;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PNameFactory;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.TableRef;
import org.apache.phoenix.schema.types.PDataType;

import com.google.common.collect.Lists;

public class PhoenixRelImplementorImpl implements PhoenixRel.Implementor {
    private final RuntimeContext runtimeContext;
	private TableRef tableRef;
	private Stack<ImplementorContext> contextStack;
	
	public PhoenixRelImplementorImpl(RuntimeContext runtimeContext) {
	    this.runtimeContext = runtimeContext;
	    this.contextStack = new Stack<ImplementorContext>();
	}
	
    @Override
    public QueryPlan visitInput(int i, PhoenixRel input) {
        return input.implement(this);
    }

	@Override
	public ColumnExpression newColumnExpression(int index) {
	    int pos = index + PhoenixTable.getStartingColumnPosition(this.tableRef.getTable());
		ColumnRef colRef = new ColumnRef(this.tableRef, pos);
		return colRef.newColumnExpression();
	}
    
    @SuppressWarnings("rawtypes")
    @Override
    public Expression newFieldAccessExpression(String variableId, int index, PDataType type) {
        TableRef variableDef = runtimeContext.getCorrelateVariableDef(variableId);
        int pos = index + PhoenixTable.getStartingColumnPosition(variableDef.getTable());
        Expression fieldAccessExpr = new ColumnRef(variableDef, pos).newColumnExpression();
        return new CorrelateVariableFieldAccessExpression(runtimeContext, variableId, fieldAccessExpr);
    }
    
    @Override
    public RuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    @Override
	public void setTableRef(TableRef tableRef) {
		this.tableRef = tableRef;
	}
    
    @Override
    public TableRef getTableRef() {
        return this.tableRef;
    }

    @Override
    public void pushContext(ImplementorContext context) {
        this.contextStack.push(context);
    }

    @Override
    public ImplementorContext popContext() {
        return contextStack.pop();
    }

    @Override
    public ImplementorContext getCurrentContext() {
        return contextStack.peek();
    }
    
    @Override
    public PTable createProjectedTable() {
        List<ColumnRef> sourceColumnRefs = Lists.<ColumnRef> newArrayList();
        int start = getCurrentContext().retainPKColumns ? 0 : PhoenixTable.getStartingColumnPosition(getTableRef().getTable());
        for (int i = start; i < getTableRef().getTable().getColumns().size(); i++) {
            sourceColumnRefs.add(new ColumnRef(getTableRef(), getTableRef().getTable().getColumns().get(i).getPosition()));
        }
        
        try {
            return TupleProjectionCompiler.createProjectedTable(getTableRef(), sourceColumnRefs, getCurrentContext().retainPKColumns);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    public RowProjector createRowProjector() {
        List<ColumnProjector> columnProjectors = Lists.<ColumnProjector>newArrayList();
        int start = PhoenixTable.getStartingColumnPosition(getTableRef().getTable());
        for (int i = start; i < getTableRef().getTable().getColumns().size(); i++) {
            PColumn column = getTableRef().getTable().getColumns().get(i);
            Expression expr = newColumnExpression(i - start); // Do not use column.position() here.
            columnProjectors.add(new ExpressionProjector(column.getName().getString(), getTableRef().getTable().getName().getString(), expr, false));
        }
        // TODO get estimate row size
        return new RowProjector(columnProjectors, 0, false);        
    }
    
    @Override
    public TupleProjector project(List<Expression> exprs) {
        KeyValueSchema.KeyValueSchemaBuilder builder = new KeyValueSchema.KeyValueSchemaBuilder(0);
        List<PColumn> columns = Lists.<PColumn>newArrayList();
        for (int i = 0; i < exprs.size(); i++) {
            String name = ParseNodeFactory.createTempAlias();
            Expression expr = exprs.get(i);
            builder.addField(expr);
            columns.add(new PColumnImpl(PNameFactory.newName(name), PNameFactory.newName(TupleProjector.VALUE_COLUMN_FAMILY),
                    expr.getDataType(), expr.getMaxLength(), expr.getScale(), expr.isNullable(),
                    i, expr.getSortOrder(), null, null, false, name));
        }
        try {
            PTable pTable = PTableImpl.makePTable(null, PName.EMPTY_NAME, PName.EMPTY_NAME,
                    PTableType.SUBQUERY, null, MetaDataProtocol.MIN_TABLE_TIMESTAMP, PTable.INITIAL_SEQ_NUM,
                    null, null, columns, null, null, Collections.<PTable>emptyList(),
                    false, Collections.<PName>emptyList(), null, null, false, false, false, null,
                    null, null, true);
            this.setTableRef(new TableRef(CalciteUtils.createTempAlias(), pTable, HConstants.LATEST_TIMESTAMP, false));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        
        return new TupleProjector(builder.build(), exprs.toArray(new Expression[exprs.size()]));        
    }

}