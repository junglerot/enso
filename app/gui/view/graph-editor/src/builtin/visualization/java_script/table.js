/** Table visualization. */

loadScript('https://cdn.jsdelivr.net/npm/ag-grid-enterprise/dist/ag-grid-enterprise.min.js')

// ============================
// === Style Initialisation ===
// ============================

loadStyle('https://cdn.jsdelivr.net/npm/ag-grid-community/styles/ag-grid.css')
loadStyle('https://cdn.jsdelivr.net/npm/ag-grid-community/styles/ag-theme-alpine.css')
loadStyleFromString(scrollbarStyle)

// ===========================
// === Table visualization ===
// ===========================

const SIDE_MARGIN = 20

class TableVisualization extends Visualization {
    // IMPORTANT: When updating this, also update the test in
    // test/Visualization_Tests/src/Default_Visualizations_Spec.enso:15 as this verifies that the
    // type names do not go out of sync. Should be removed once
    // https://github.com/enso-org/enso/issues/5195 is implemented.
    static inputType =
        'Standard.Table.Data.Table.Table | Standard.Table.Data.Column.Column | Standard.Table.Data.Row.Row |Standard.Base.Data.Vector.Vector | Standard.Base.Data.Array.Array | Standard.Base.Data.Map.Map | Any '
    static label = 'Table'

    constructor(data) {
        super(data)
        this.setRowLimitAndPage(1000)
        this.widths = {}
    }

    setRowLimitAndPage(row_limit) {
        if (this.row_limit !== row_limit) {
            this.row_limit = row_limit
            this.setPreprocessor(
                'Standard.Visualization.Table.Visualization',
                'prepare_visualization',
                this.row_limit.toString()
            )
        }
    }

    onDataReceived(data) {
        function addRowIndex(data) {
            return data.map((row, i) => ({ ['#']: i, ...row }))
        }

        function hasExactlyKeys(keys, obj) {
            return Object.keys(obj).length === keys.length && keys.every(k => obj.hasOwnProperty(k))
        }

        function isObjectMatrix(data) {
            let isList = Array.isArray(data) && data[0]
            if (!isList || !(typeof data[0] === 'object')) return false
            let firstKeys = Object.keys(data[0])
            return data.every(obj => hasExactlyKeys(firstKeys, obj))
        }

        function isMatrix(data) {
            let isList = Array.isArray(data) && data[0]
            if (!isList) return false
            let firstIsArray = Array.isArray(data[0])
            if (!firstIsArray) return false
            let firstLen = data[0].length
            return data.every(d => d.length === firstLen)
        }

        function toRender(content) {
            if (content instanceof Array) {
                if (isMatrix(content)) {
                    return `[Vector ${content.length} rows x ${content[0].length} cols]`
                } else if (isObjectMatrix(content)) {
                    return `[Table ${content.length} rows x ${Object.keys(content[0]).length} cols]`
                } else {
                    return `[Vector ${content.length} items]`
                }
            }

            if (content instanceof Object) {
                const type = content.type
                if (content['_display_text_']) {
                    return content['_display_text_']
                } else {
                    return `{ ${type} Object }`
                }
            }

            return content
        }

        function escapeHTML(str) {
            const mapping = { '&': '&amp;', '<': '&lt;', '"': '&quot;', "'": '&#39;', '>': '&gt;' }
            return str.replace(/[&<>"']/g, m => mapping[m])
        }

        function cellRenderer(params) {
            if (params.value === null) {
                return '<span style="color:grey; font-style: italic;">Nothing</span>'
            } else if (params.value === undefined) {
                return ''
            } else if (params.value === '') {
                return '<span style="color:grey; font-style: italic;">Empty</span>'
            }
            return escapeHTML(params.value.toString())
        }

        if (!this.tabElem) {
            while (this.dom.firstChild) {
                this.dom.removeChild(this.dom.lastChild)
            }

            const style =
                '.ag-theme-alpine { --ag-grid-size: 3px; --ag-list-item-height: 20px; display: inline; }\n' +
                '.vis-status-bar { height: 20px; background-color: white; font-size:14px; white-space:nowrap; padding: 0 5px; overflow:hidden; border-radius: 16px 16px 0 0 }\n' +
                '.vis-tbl-grid { height: calc(100% - 20px); width: 100%; }\n'
            const styleElem = document.createElement('style')
            styleElem.innerHTML = style
            this.dom.appendChild(styleElem)

            const statusElem = document.createElement('div')
            statusElem.setAttributeNS(null, 'id', 'vis-tbl-status')
            statusElem.setAttributeNS(null, 'class', 'vis-status-bar')
            this.dom.appendChild(statusElem)
            this.statusElem = statusElem

            const gridElem = document.createElement('div')
            gridElem.setAttributeNS(null, 'id', 'vis-tbl-grid')
            gridElem.className = 'vis-tbl-grid'
            this.dom.appendChild(gridElem)

            const tabElem = document.createElement('div')
            tabElem.setAttributeNS(null, 'id', 'vis-tbl-view')
            tabElem.setAttributeNS(null, 'class', 'scrollable ag-theme-alpine')
            gridElem.appendChild(tabElem)
            this.tabElem = tabElem

            this.agGridOptions = {
                rowData: [],
                columnDefs: [],
                defaultColDef: {
                    editable: false,
                    sortable: true,
                    filter: true,
                    resizable: true,
                    minWidth: 25,
                    headerValueGetter: params => params.colDef.field,
                    cellRenderer: cellRenderer,
                },
                onFirstDataRendered: () => this.updateColumnWidths(),
                onRowDataUpdated: () => this.updateColumnWidths(),
                onColumnResized: e => this.lockColumnSize(e),
                suppressFieldDotNotation: true,
                enableRangeSelection: true,
            }

            if (typeof AG_GRID_LICENSE_KEY !== 'undefined') {
                agGrid.LicenseManager.setLicenseKey(AG_GRID_LICENSE_KEY)
            } else {
                console.warn('The AG_GRID_LICENSE_KEY is not defined.')
            }

            this.agGrid = new agGrid.Grid(tabElem, this.agGridOptions)
        }

        let parsedData = typeof data === 'string' ? JSON.parse(data) : data

        let columnDefs = []
        let rowData = []
        let dataTruncated = false

        if (parsedData.error !== undefined) {
            columnDefs = [
                {
                    field: 'Error',
                    cellStyle: { 'white-space': 'normal' },
                },
            ]
            rowData = [{ Error: parsedData.error }]
        } else if (parsedData.type === 'Matrix') {
            let defs = [{ field: '#' }]
            for (let i = 0; i < parsedData.column_count; i++) {
                defs.push({ field: i.toString() })
            }
            columnDefs = defs
            rowData = addRowIndex(parsedData.json)
            dataTruncated = parsedData.all_rows_count !== parsedData.json.length
        } else if (parsedData.type === 'Object_Matrix') {
            let defs = [{ field: '#' }]
            let keys = {}
            parsedData.json.forEach(val => {
                if (val) {
                    Object.keys(val).forEach(k => {
                        if (!keys[k]) {
                            keys[k] = true
                            defs.push({ field: k })
                        }
                    })
                }
            })
            columnDefs = defs
            rowData = addRowIndex(parsedData.json)
            dataTruncated = parsedData.all_rows_count !== parsedData.json.length
        } else if (isMatrix(parsedData.json)) {
            // Kept to allow visualization from older versions of the backend.
            columnDefs = [
                { field: '#' },
                ...parsedData.json[0].map((_, i) => ({ field: i.toString() })),
            ]
            rowData = addRowIndex(parsedData.json)
            dataTruncated = parsedData.all_rows_count !== parsedData.json.length
        } else if (isObjectMatrix(parsedData.json)) {
            // Kept to allow visualization from older versions of the backend.
            let firstKeys = [{ field: '#' }, ...Object.keys(parsedData.json[0])]
            columnDefs = firstKeys.map(field => ({ field }))
            rowData = addRowIndex(parsedData.json)
            dataTruncated = parsedData.all_rows_count !== parsedData.json.length
        } else if (Array.isArray(parsedData.json)) {
            columnDefs = [{ field: '#' }, { field: 'Value' }]
            rowData = parsedData.json.map((row, i) => ({ ['#']: i, Value: toRender(row) }))
            dataTruncated = parsedData.all_rows_count !== parsedData.json.length
        } else if (parsedData.json !== undefined) {
            columnDefs = [{ field: 'Value' }]
            rowData = [{ Value: toRender(parsedData.json) }]
        } else {
            const indices_header = (parsedData.indices_header ? parsedData.indices_header : []).map(
                h => ({ field: h })
            )
            columnDefs = [...indices_header, ...parsedData.header.map(h => ({ field: h }))]

            const rows =
                parsedData.data && parsedData.data.length > 0
                    ? parsedData.data[0].length
                    : parsedData.indices && parsedData.indices.length > 0
                    ? parsedData.indices[0].length
                    : 0
            rowData = Array.apply(null, Array(rows)).map((_, i) => {
                const row = {}
                const shift = parsedData.indices ? parsedData.indices.length : 0
                columnDefs.map(
                    (h, j) =>
                        (row[h.field] = toRender(
                            j < shift ? parsedData.indices[j][i] : parsedData.data[j - shift][i]
                        ))
                )
                return row
            })

            dataTruncated = parsedData.all_rows_count !== rowData.length
        }

        // Update Status Bar
        this.updateStatusBarControls(
            parsedData.all_rows_count === undefined ? 1 : parsedData.all_rows_count,
            dataTruncated
        )

        // If an existing grid, merge width from manually sized columns.
        var newWidths = {}
        var mergedColumnDefs = columnDefs.map(columnDef => {
            if (this.widths[columnDef.field]) {
                columnDef.width = this.widths[columnDef.field]
                newWidths[columnDef.field] = columnDef.width
            }
            return columnDef
        })
        this.widths = newWidths

        // If data is truncated, we cannot rely on sorting/filtering so will disable.
        this.agGridOptions.defaultColDef.filter = !dataTruncated
        this.agGridOptions.defaultColDef.sortable = !dataTruncated
        this.agGridOptions.api.setColumnDefs(mergedColumnDefs)
        this.agGridOptions.api.setRowData(rowData)
    }

    makeOption(value, label) {
        const optionElem = document.createElement('option')
        optionElem.value = value
        optionElem.appendChild(document.createTextNode(label))
        return optionElem
    }

    // Updates the status bar to reflect the current row limit, shown at top of the visualization.
    // - Creates the row dropdown.
    // - Updated the row counts and filter available options.
    updateStatusBarControls(all_rows_count, dataTruncated) {
        if (this.statusElem.childElementCount === 0) {
            const selectElem = document.createElement('select')
            selectElem.name = 'row-limit'
            selectElem.addEventListener('change', e => {
                this.setRowLimitAndPage(e.target.value)
            })
            this.statusElem.appendChild(selectElem)

            const rowCountSpanElem = document.createElement('span')
            this.statusElem.appendChild(rowCountSpanElem)
        }

        // Update row limit dropdown and row count
        const rowCountElem = this.statusElem.getElementsByTagName('span')[0]
        const rowLimitElem = this.statusElem.children.namedItem('row-limit')
        if (all_rows_count > 1000) {
            rowLimitElem.style.display = 'inline-block'
            const rowCounts = [1000, 2500, 5000, 10000, 25000, 50000, 100000].filter(
                r => r <= all_rows_count
            )
            if (all_rows_count < 100000 && rowCounts.indexOf(all_rows_count) === -1) {
                rowCounts.push(all_rows_count)
            }
            rowLimitElem.innerHTML = ''
            rowCounts.forEach(r => {
                const option = this.makeOption(r, r.toString())
                rowLimitElem.appendChild(option)
            })
            rowLimitElem.value = this.row_limit

            rowCountElem.innerHTML = dataTruncated
                ? ` of ${all_rows_count} rows (Sorting/Filtering disabled).`
                : ` rows.`
        } else {
            rowLimitElem.style.display = 'none'
            rowCountElem.innerHTML = all_rows_count === 1 ? '1 row.' : `${all_rows_count} rows.`
        }
    }

    updateColumnWidths() {
        // Resize columns to fit the table width unless the user manually resized them.
        const cols = this.agGridOptions.columnApi
            .getAllGridColumns()
            .filter(c => !this.widths[c.colDef.field])

        // Resize based on the data and then shrink any columns that are too wide.
        this.agGridOptions.columnApi.autoSizeColumns(cols)
    }

    lockColumnSize(e) {
        // Check if the resize is finished, and it's not from the API (which is triggered by us).
        if (!e.finished || e.source === 'api') {
            return
        }

        // If the user manually resized a column, we don't want to auto-size it on a resize.
        const manuallySized = e.source !== 'autosizeColumns'
        for (const column of e.columns) {
            const field = column.colDef.field
            this.widths[field] = manuallySized ? column.actualWidth : undefined
        }
    }

    setSize(size) {
        this.dom.setAttributeNS(null, 'width', size[0])
        this.dom.setAttributeNS(null, 'height', size[1])
    }
}

return TableVisualization
